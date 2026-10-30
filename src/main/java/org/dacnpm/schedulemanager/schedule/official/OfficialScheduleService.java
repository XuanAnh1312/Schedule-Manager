package org.dacnpm.schedulemanager.schedule.official;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.events.ScheduleEvent;
import org.dacnpm.schedulemanager.schedule.events.ScheduleEventSink;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService.Preview;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleRepository.OfficialAssignment;
import org.dacnpm.schedulemanager.schedule.plan.PlanException;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftNotFoundException;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCandidate;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.Violation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sửa lịch chính thức có kiểm soát (TW-06): thêm người, thay người, hủy phân công. Chữ ký khớp hợp đồng
 * {@code AssignmentMutationService}/{@code AssignmentValidationService} của module đổi ca/nghỉ phép (Bảo), để adapter chỉ
 * cần chuyển tiếp.
 *
 * <p>Mỗi thao tác trong một giao dịch: khóa dòng kỳ, kiểm tra bằng cùng {@link AssignmentValidator}, ghi có điều kiện
 * (người đang giữ + version), ghi lịch sử, phát sự kiện. Vi phạm bắt buộc luôn bị từ chối; vi phạm thuộc nhóm ngoại lệ
 * chỉ được ghi khi có ngoại lệ hợp lệ (quyền riêng + bản xem trước + xác nhận + lý do).
 */
@Profile("sqlserver")
@Service
public class OfficialScheduleService {

    /** Ngoại lệ đi kèm thay đổi: lý do, token của bản xem trước và xác nhận. */
    public record ExceptionOverride(String reason, String previewToken, Boolean confirm) {
    }

    /** @param exceptionId ngoại lệ được ghi cho thay đổi này, null nếu không cần */
    public record ChangeResult(String assignmentId, String shiftId, String userId, String exceptionId,
                               List<String> warnings) {
    }

    private final OfficialScheduleRepository official;
    private final ShiftRepository shifts;
    private final ScheduleDataRepository data;
    private final AssignmentValidator validator;
    private final ExceptionService exceptions;
    private final PlanRepository plans;
    private final ScheduleEventSink events;

    public OfficialScheduleService(OfficialScheduleRepository official, ShiftRepository shifts,
                                   ScheduleDataRepository data, AssignmentValidator validator,
                                   ExceptionService exceptions, PlanRepository plans, ScheduleEventSink events) {
        this.official = official;
        this.shifts = shifts;
        this.data = data;
        this.validator = validator;
        this.exceptions = exceptions;
        this.plans = plans;
        this.events = events;
    }

    /**
     * Kiểm tra cho module đổi ca/ca trống/nghỉ phép tại thời điểm duyệt.
     *
     * @param replacingUserId     người đang giữ vị trí; khi ca theo Level, người nhận phải đáp ứng Level của người đó
     * @param ignoreAssignmentIds phân công sẽ bị gỡ trong cùng thao tác (không tính giờ/trùng giờ)
     */
    @Transactional(readOnly = true)
    public ValidationResult validate(String userId, String shiftId, String replacingUserId,
                                     Set<String> ignoreAssignmentIds, String requiredLevelId) {
        ShiftDetail shift = activeShift(shiftId);
        ScheduleContext ctx = load(shift).context(ignoreAssignmentIds == null ? Set.of() : ignoreAssignmentIds);
        String level = requiredLevelId != null ? requiredLevelId : levelOf(ctx, replacingUserId, shiftId);
        return validator.validate(ctx, new AssignmentCandidate(userId, shiftId, level));
    }

    /** Xem trước vi phạm (cần quyền ngoại lệ) trước khi thêm/thay người trên lịch chính thức. */
    @Transactional(readOnly = true)
    public Preview preview(String shiftId, String userId, String replacingAssignmentId, String actorId) {
        Set<String> ignore = Set.of();
        String replacing = null;
        if (replacingAssignmentId != null) {
            OfficialAssignment a = confirmed(replacingAssignmentId);
            ignore = Set.of(a.id());
            replacing = a.userId();
        }
        return exceptions.previewOfficial(shiftId, userId, validate(userId, shiftId, replacing, ignore, null), actorId);
    }

    @Transactional
    public ChangeResult addAssignment(String shiftId, String userId, String actorId, String reason, ExceptionOverride override) {
        ShiftDetail shift = activeShift(shiftId);
        String periodId = lockPeriodOf(shift);
        ValidationResult result = validate(userId, shiftId, null, Set.of(), null);
        String exceptionId = guard(shiftId, userId, result, override, actorId);
        String id = official.addOrReactivate(shiftId, userId, actorId, reason, null);
        int version = official.find(id).map(OfficialAssignment::version).orElse(1);
        shifts.writeHistory("manual_assign", "Thêm phân công vào lịch chính thức",
                id + ": " + userId + " vào ca " + shiftId + " (kỳ " + periodId + ")"
                        + (exceptionId == null ? "." : ", ngoại lệ " + exceptionId + "."), actorId, null, null, reason);
        events.publish(ScheduleEvent.of(ScheduleEvent.ASSIGNMENT_ADDED, "assignment", id, version, actorId,
                List.of(userId), "Bạn được phân thêm ca", label(shift) + (reason == null ? "" : ". Lý do: " + reason),
                "my-schedule"));
        return new ChangeResult(id, shiftId, userId, exceptionId, List.of());
    }

    /** Thay người: hủy phân công cũ (nếu vẫn do expectedUserId giữ) và phân cho newUserId. */
    @Transactional
    public ChangeResult reassign(String assignmentId, String expectedUserId, String newUserId, String actorId,
                                 String reason, ExceptionOverride override) {
        OfficialAssignment current = confirmed(assignmentId);
        requireHolder(current, expectedUserId);
        ShiftDetail shift = activeShift(current.shiftId());
        lockPeriodOf(shift);
        ValidationResult result = validate(newUserId, current.shiftId(), current.userId(), Set.of(current.id()), null);
        String exceptionId = guard(current.shiftId(), newUserId, result, override, actorId);
        if (!official.cancel(current.id(), expectedUserId, current.version(), "Thay bằng " + newUserId)) {
            throw conflict();
        }
        String newId = official.addOrReactivate(current.shiftId(), newUserId, actorId, reason, null);
        shifts.writeHistory("manual_reassign", "Thay người trên lịch chính thức",
                current.shiftId() + ": " + expectedUserId + " → " + newUserId
                        + (exceptionId == null ? "." : ", ngoại lệ " + exceptionId + "."), actorId, null, null, reason);
        events.publish(ScheduleEvent.of(ScheduleEvent.SCHEDULE_CHANGED, "assignment", current.id(), current.version() + 1,
                actorId, List.of(expectedUserId, newUserId), "Lịch làm việc thay đổi",
                label(shift) + ": " + expectedUserId + " được thay bằng " + newUserId + ".", "my-schedule"));
        return new ChangeResult(newId, current.shiftId(), newUserId, exceptionId, List.of());
    }

    /** Hủy phân công (ví dụ nghỉ phép được duyệt) nếu vẫn do expectedUserId giữ; cảnh báo nếu ca thành thiếu người. */
    @Transactional
    public ChangeResult cancelAssignment(String assignmentId, String expectedUserId, String actorId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Lý do hủy phân công không được để trống.");
        }
        OfficialAssignment current = confirmed(assignmentId);
        requireHolder(current, expectedUserId);
        ShiftDetail shift = activeShift(current.shiftId());
        lockPeriodOf(shift);
        if (!official.cancel(current.id(), expectedUserId, current.version(), reason.trim())) {
            throw conflict();
        }
        int remaining = official.confirmedByShifts(List.of(current.shiftId())).size();
        int minimum = shift.shift().minRequired() != null ? shift.shift().minRequired() : shift.shift().totalRequired();
        List<String> warnings = remaining < minimum
                ? List.of("Ca " + shift.shift().name() + " còn " + remaining + "/" + minimum + " người tối thiểu.")
                : List.of();
        shifts.writeHistory("manual_unassign", "Hủy phân công trên lịch chính thức",
                current.id() + ": " + expectedUserId + " khỏi ca " + current.shiftId() + ".", actorId, null, null,
                reason.trim());
        events.publish(ScheduleEvent.of(ScheduleEvent.SCHEDULE_CHANGED, "assignment", current.id(), current.version() + 1,
                actorId, List.of(expectedUserId), "Ca của bạn đã bị hủy", label(shift) + ". Lý do: " + reason.trim(),
                "my-schedule"));
        return new ChangeResult(current.id(), current.shiftId(), expectedUserId, null, warnings);
    }

    @Transactional(readOnly = true)
    public List<OfficialAssignment> confirmedOfUser(String userId, LocalDate from, LocalDate to) {
        return official.confirmedOfUser(userId, from, to);
    }

    /** null nếu hợp lệ; id ngoại lệ nếu vi phạm thuộc nhóm ngoại lệ và có ngoại lệ hợp lệ; ngược lại ném lỗi. */
    private String guard(String shiftId, String userId, ValidationResult result, ExceptionOverride override, String actorId) {
        if (result.valid()) {
            return null;
        }
        List<String> details = result.violations().stream().map(Violation::message).toList();
        if (!result.overridable()) {
            throw PlanException.of(PlanException.HARD_VIOLATIONS, 422, "Phân công vi phạm ràng buộc bắt buộc.", details);
        }
        if (override == null) {
            throw PlanException.of(PlanException.EXCEPTION_REQUIRED, 422,
                    "Phân công cần ngoại lệ: xem trước rồi gửi lại kèm lý do, previewToken và confirm.", details);
        }
        return exceptions.approveOfficial(shiftId, userId, result, override.reason(), override.previewToken(),
                override.confirm(), actorId);
    }

    private OfficialAssignment confirmed(String assignmentId) {
        OfficialAssignment a = official.find(assignmentId)
                .orElseThrow(() -> PlanException.notFound("Không tìm thấy phân công " + assignmentId + "."));
        if (!a.confirmed()) {
            throw PlanException.conflict("Phân công " + assignmentId + " đã bị hủy.");
        }
        return a;
    }

    private static void requireHolder(OfficialAssignment a, String expectedUserId) {
        if (expectedUserId == null || !expectedUserId.equals(a.userId())) {
            throw PlanException.conflict("Phân công " + a.id() + " hiện do " + a.userId() + " giữ, không phải "
                    + expectedUserId + ". Hãy tải lại.");
        }
    }

    private ShiftDetail activeShift(String shiftId) {
        ShiftDetail shift = shifts.findById(shiftId)
                .orElseThrow(() -> new ShiftNotFoundException("Không tìm thấy ca " + shiftId + "."));
        if (shift.cancelled()) {
            throw new IllegalArgumentException("Ca " + shiftId + " đã hủy.");
        }
        return shift;
    }

    private String lockPeriodOf(ShiftDetail shift) {
        String periodId = shifts.periodIdFor(shift.shift().date())
                .orElseThrow(() -> new IllegalArgumentException("Ngày " + shift.shift().date() + " không thuộc kỳ nào."));
        plans.lockPeriod(periodId);
        return periodId;
    }

    private ScheduleData load(ShiftDetail shift) {
        String periodId = shifts.periodIdFor(shift.shift().date())
                .orElseThrow(() -> new IllegalArgumentException("Ngày " + shift.shift().date() + " không thuộc kỳ nào."));
        return data.load(periodId).orElseThrow(() -> PlanException.notFound("Không tìm thấy kỳ " + periodId + "."));
    }

    /** Level của người đang giữ vị trí, nếu ca có yêu cầu Level đó. */
    private static String levelOf(ScheduleContext ctx, String replacingUserId, String shiftId) {
        if (replacingUserId == null) {
            return null;
        }
        Employee replaced = ctx.employee(replacingUserId);
        ShiftInfo shift = ctx.shift(shiftId);
        if (replaced == null || shift == null || replaced.levelId() == null) {
            return null;
        }
        return shift.levelRequirements().containsKey(replaced.levelId()) ? replaced.levelId() : null;
    }

    private static String label(ShiftDetail shift) {
        return shift.shift().name() + " " + shift.shift().date() + " " + shift.shift().startTime() + "–"
                + shift.shift().endTime();
    }

    private static PlanException conflict() {
        return PlanException.conflict("Phân công đã được người khác thay đổi trong lúc xử lý. Hãy tải lại rồi thử lại.");
    }
}
