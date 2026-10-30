package org.dacnpm.schedulemanager.schedule.validation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftNotFoundException;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cổng kiểm tra phân công dùng chung cho các luồng ngoài phân lịch tự động: gán thủ công/kéo thả/nhận ca,
 * đổi ca, công bố và nghỉ phép tác động lịch. Mọi luồng đều đi qua {@link AssignmentValidator}, nên cùng một bộ
 * ràng buộc (trùng giờ, không thể làm, active, địa điểm, Level, kỹ năng/chứng chỉ, giờ ngày/tuần/kỳ, nghỉ tối
 * thiểu, ngày liên tiếp, giới hạn hợp đồng). Chỉ đọc, không ghi dữ liệu.
 */
@Profile("sqlserver")
@Service
public class AssignmentCheckService {

    static final int MAX_LEAVE_SPAN_DAYS = 366;

    /** Kết quả đổi ca: người A sang ca B và người B sang ca A. */
    public record SwapCheck(ValidationResult userAToShiftB, ValidationResult userBToShiftA) {
        public boolean allowed() {
            return userAToShiftB.valid() && userBToShiftA.valid();
        }
    }

    /**
     * Kiểm tra một ca trước khi công bố. hardViolations là vi phạm bắt buộc (không thể công bố);
     * exceptionViolations là vi phạm chỉ có thể công bố khi có ngoại lệ được duyệt (TW-06).
     */
    public record ShiftPublishCheck(String shiftId, int required, int filled, boolean understaffed,
                                    List<Violation> hardViolations, List<Violation> exceptionViolations) {
    }

    public record PublishCheck(List<ShiftPublishCheck> shifts) {
        /** Không có vi phạm nào, kể cả loại cần ngoại lệ. Ca thiếu người do chính sách thiếu người quyết định. */
        public boolean publishable() {
            return shifts.stream().allMatch(s -> s.hardViolations().isEmpty() && s.exceptionViolations().isEmpty());
        }

        public boolean needsException() {
            return shifts.stream().noneMatch(s -> !s.hardViolations().isEmpty())
                    && shifts.stream().anyMatch(s -> !s.exceptionViolations().isEmpty());
        }

        public boolean anyUnderstaffed() {
            return shifts.stream().anyMatch(ShiftPublishCheck::understaffed);
        }
    }

    public record LeaveImpact(String assignmentId, String shiftId, String shiftName, LocalDate date,
                              int required, int filledAfterLeave, boolean understaffed) {
    }

    public record LeaveCheck(List<LeaveImpact> impacts) {
        public boolean causesUnderstaffing() {
            return impacts.stream().anyMatch(LeaveImpact::understaffed);
        }
    }

    private final ShiftRepository shifts;
    private final ScheduleDataRepository data;
    private final AssignmentValidator validator;

    public AssignmentCheckService(ShiftRepository shifts, ScheduleDataRepository data, AssignmentValidator validator) {
        this.shifts = shifts;
        this.data = data;
        this.validator = validator;
    }

    /** Gán thủ công, kéo thả hoặc nhân viên nhận ca: kiểm tra thêm một nhân viên vào một ca. */
    @Transactional(readOnly = true)
    public ValidationResult checkAssignment(AssignmentCandidate candidate) {
        ShiftDetail shift = activeShift(candidate.shiftId());
        ScheduleData d = loadFor(shift);
        return validator.validate(context(d, Set.of()), candidate);
    }

    /**
     * Đổi ca: hai phân công cũ được thay thế bởi hai phân công mới, nên không bị tính trùng với nhau; việc người A
     * sang ca B được tính khi kiểm tra người B sang ca A.
     */
    @Transactional(readOnly = true)
    public SwapCheck checkSwap(String shiftIdA, String userIdA, String shiftIdB, String userIdB) {
        if (shiftIdA.equals(shiftIdB) || userIdA.equals(userIdB)) {
            throw new IllegalArgumentException("Đổi ca cần hai ca khác nhau và hai nhân viên khác nhau.");
        }
        ShiftDetail a = activeShift(shiftIdA);
        ShiftDetail b = activeShift(shiftIdB);
        ScheduleData d = loadFor(a);
        requireSamePeriod(d, b);
        Assignment assignmentA = findAssignment(d, shiftIdA, userIdA);
        Assignment assignmentB = findAssignment(d, shiftIdB, userIdB);

        ScheduleContext ctx = context(d, Set.of(assignmentA.id(), assignmentB.id()));
        ValidationResult aToB = validator.validate(ctx, new AssignmentCandidate(userIdA, shiftIdB, null));
        ctx.addProposed(new Assignment("swap_" + assignmentA.id(), shiftIdB, userIdA, Assignment.CONFIRMED));
        ValidationResult bToA = validator.validate(ctx, new AssignmentCandidate(userIdB, shiftIdA, null));
        return new SwapCheck(aToB, bToA);
    }

    /** Kiểm tra lại mọi phân công hiện có của các ca trước khi công bố, và nêu ca thiếu người. */
    @Transactional(readOnly = true)
    public PublishCheck checkPublish(List<String> shiftIds) {
        if (shiftIds == null || shiftIds.isEmpty()) {
            throw new IllegalArgumentException("Phải chỉ định ít nhất một ca để kiểm tra công bố.");
        }
        List<ShiftDetail> details = shiftIds.stream().distinct().map(this::activeShift).toList();
        ScheduleData d = loadFor(details.get(0));
        details.forEach(s -> requireSamePeriod(d, s));
        Map<String, ShiftInfo> byId = new HashMap<>();
        d.shifts().forEach(s -> byId.put(s.id(), s));

        List<ShiftPublishCheck> result = new ArrayList<>();
        for (ShiftDetail detail : details) {
            ShiftInfo info = byId.get(detail.id());
            List<Assignment> assignments = d.savedAssignments().stream()
                    .filter(a -> a.shiftId().equals(detail.id())).toList();
            List<Violation> hard = new ArrayList<>();
            List<Violation> exception = new ArrayList<>();
            for (Assignment assignment : assignments) {
                // Bỏ chính phân công đang kiểm tra khỏi ngữ cảnh để không bị báo "đã được phân" hoặc tính giờ hai lần.
                ScheduleContext ctx = context(d, Set.of(assignment.id()));
                ValidationResult check = validator.validate(ctx,
                        new AssignmentCandidate(assignment.userId(), detail.id(), null));
                for (Violation v : check.violations()) {
                    (v.code().overridable() ? exception : hard).add(v);
                }
            }
            int required = info == null ? detail.shift().totalRequired() : info.totalRequired();
            result.add(new ShiftPublishCheck(detail.id(), required, assignments.size(),
                    assignments.size() < required, hard, exception));
        }
        return new PublishCheck(result);
    }

    /**
     * Nghỉ phép tác động lịch: liệt kê các phân công của nhân viên rơi vào khoảng nghỉ và cho biết ca nào
     * sẽ thiếu người nếu đơn được duyệt. Khoảng nghỉ phải nằm trong cùng một kỳ.
     */
    @Transactional(readOnly = true)
    public LeaveCheck checkLeave(String userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Khoảng nghỉ không hợp lệ.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_LEAVE_SPAN_DAYS) {
            throw new IllegalArgumentException("Khoảng nghỉ tối đa " + MAX_LEAVE_SPAN_DAYS + " ngày.");
        }
        String periodId = shifts.periodIdFor(from)
                .orElseThrow(() -> new IllegalArgumentException("Ngày " + from + " không thuộc kỳ nào."));
        ScheduleData d = data.load(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ " + periodId + "."));
        if (!d.period().contains(to)) {
            throw new IllegalArgumentException("Khoảng nghỉ phải nằm trong cùng một kỳ (" + d.period().name() + ").");
        }
        Map<String, ShiftInfo> byId = new HashMap<>();
        d.shifts().forEach(s -> byId.put(s.id(), s));

        List<LeaveImpact> impacts = new ArrayList<>();
        for (Assignment assignment : d.savedAssignments()) {
            if (!assignment.userId().equals(userId)) {
                continue;
            }
            ShiftInfo shift = byId.get(assignment.shiftId());
            if (shift == null || !touches(shift, from, to)) {
                continue;
            }
            long filled = d.savedAssignments().stream().filter(a -> a.shiftId().equals(shift.id())).count();
            int filledAfter = (int) filled - 1;
            impacts.add(new LeaveImpact(assignment.id(), shift.id(), shift.name(), shift.date(),
                    shift.totalRequired(), filledAfter, filledAfter < shift.totalRequired()));
        }
        impacts.sort(java.util.Comparator.comparing(LeaveImpact::date).thenComparing(LeaveImpact::shiftId));
        return new LeaveCheck(impacts);
    }

    private static boolean touches(ShiftInfo shift, LocalDate from, LocalDate to) {
        return WorkTime.workMinutesByDay(shift).keySet().stream().anyMatch(day -> !day.isBefore(from) && !day.isAfter(to));
    }

    private ShiftDetail activeShift(String shiftId) {
        ShiftDetail shift = shifts.findById(shiftId)
                .orElseThrow(() -> new ShiftNotFoundException("Không tìm thấy ca " + shiftId + "."));
        if (shift.cancelled()) {
            throw new IllegalArgumentException("Ca " + shiftId + " đã hủy.");
        }
        return shift;
    }

    private ScheduleData loadFor(ShiftDetail shift) {
        String periodId = shifts.periodIdFor(shift.shift().date())
                .orElseThrow(() -> new IllegalArgumentException("Ngày " + shift.shift().date() + " không thuộc kỳ nào."));
        return data.load(periodId).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ " + periodId + "."));
    }

    private void requireSamePeriod(ScheduleData d, ShiftDetail shift) {
        if (!d.period().contains(shift.shift().date())) {
            throw new IllegalArgumentException("Các ca phải thuộc cùng một kỳ (" + d.period().name() + ").");
        }
    }

    private static Assignment findAssignment(ScheduleData d, String shiftId, String userId) {
        return d.savedAssignments().stream()
                .filter(a -> a.shiftId().equals(shiftId) && a.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Nhân viên " + userId + " chưa được phân vào ca " + shiftId + "."));
    }

    private static ScheduleContext context(ScheduleData d, Set<String> replacedAssignmentIds) {
        return new ScheduleContext(d.period(), d.rules(), d.employees(), d.shifts(), d.availability(),
                d.blockedDates(), d.savedAssignments(), replacedAssignmentIds);
    }
}
