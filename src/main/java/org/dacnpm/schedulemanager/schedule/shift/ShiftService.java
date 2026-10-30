package org.dacnpm.schedulemanager.schedule.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCandidate;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.Violation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý ca (TW-01). Nhân viên bắt buộc/ưu tiên của ca phải qua cùng bộ kiểm tra {@link AssignmentValidator}
 * như phân lịch tự động: vi phạm bắt buộc làm ca bị từ chối; vi phạm thuộc nhóm có thể xin ngoại lệ
 * (thời gian rảnh) chỉ trả về dạng cảnh báo vì nhân viên có thể chưa đăng ký rảnh lúc tạo ca.
 */
@Profile("sqlserver")
@Service
public class ShiftService {

    static final int MAX_BATCH = 200;
    static final int MAX_SPAN_DAYS = 366;

    public record CancelResult(ShiftDetail shift, Set<String> affectedUserIds) {
    }

    public record RepeatRequest(LocalDate until, Set<Integer> weekdays, Integer everyNWeeks) {
    }

    private final ShiftRepository shifts;
    private final ScheduleDataRepository data;
    private final AssignmentValidator validator;
    private final ObjectMapper mapper;

    public ShiftService(ShiftRepository shifts, ScheduleDataRepository data, AssignmentValidator validator,
                        ObjectMapper mapper) {
        this.shifts = shifts;
        this.data = data;
        this.validator = validator;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ShiftDetail get(String id) {
        return shifts.findById(id).orElseThrow(() -> new ShiftNotFoundException("Không tìm thấy ca " + id + "."));
    }

    @Transactional(readOnly = true)
    public List<ShiftDetail> search(LocalDate from, LocalDate to, String locationId, boolean includeCancelled) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Khoảng ngày không hợp lệ.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("Khoảng ngày tối đa " + MAX_SPAN_DAYS + " ngày.");
        }
        return shifts.search(from, to, locationId, includeCancelled);
    }

    @Transactional
    public ShiftDetail create(ShiftRequest request, String actorId) {
        return createOne(request, actorId, false, null, null);
    }

    /** Tạo hàng loạt trong một giao dịch: một ca lỗi thì không ca nào được lưu. */
    @Transactional
    public List<ShiftDetail> createMany(List<ShiftRequest> requests, String actorId) {
        if (requests == null || requests.isEmpty() || requests.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Số ca tạo hàng loạt phải từ 1 đến " + MAX_BATCH + ".");
        }
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            int index = i + 1;
            ShiftConfigRules.check(requests.get(i)).forEach(e -> errors.add("Ca #" + index + ": " + e));
        }
        if (!errors.isEmpty()) {
            throw new ShiftRejectedException(errors, List.of(), List.of());
        }
        return requests.stream().map(r -> createOne(r, actorId, false, null, null)).toList();
    }

    /** Ca khẩn cấp: bắt buộc có lý do; ghi lịch sử riêng và đánh dấu is_emergency. */
    @Transactional
    public ShiftDetail addEmergency(ShiftRequest request, String reason, String actorId) {
        requireReason(reason);
        return createOne(request, actorId, true, null, reason.trim());
    }

    @Transactional
    public ShiftDetail update(String id, int expectedVersion, ShiftRequest request, String actorId) {
        ShiftDetail current = get(id);
        if (current.cancelled()) {
            throw new IllegalArgumentException("Ca đã hủy, không thể sửa.");
        }
        if ("published".equals(current.status())) {
            throw new IllegalArgumentException("Ca đã công bố, không sửa trực tiếp; cần đi qua luồng duyệt/ngoại lệ.");
        }
        if (current.version() != expectedVersion) {
            throw versionConflict();
        }
        checkConfig(request);
        Set<String> assignees = shifts.confirmedAssignees(id);
        int capacity = request.maxAllowed() != null ? request.maxAllowed() : request.totalRequired();
        if (assignees.size() > capacity) {
            throw new ShiftRejectedException(List.of("Ca đang có " + assignees.size()
                    + " phân công, vượt số người tối đa mới (" + capacity + ")."), List.of(), List.of());
        }
        // Những người đã được phân công phải còn hợp lệ với cấu hình mới (giờ, ngày, kỹ năng...).
        checkPeople(id, request, assignees);
        if (!shifts.update(id, expectedVersion, request)) {
            throw versionConflict();
        }
        ShiftDetail updated = get(id);
        audit("shift_update", "Sửa ca " + updated.shift().name(), id, actorId, current, updated, null);
        return updated;
    }

    @Transactional
    public void delete(String id, String actorId) {
        ShiftDetail current = get(id);
        if (shifts.countReferences(id) > 0) {
            throw new IllegalArgumentException("Ca đã có phân công hoặc phương án nháp, không thể xóa; hãy hủy ca kèm lý do.");
        }
        audit("shift_delete", "Xóa ca " + current.shift().name(), id, actorId, current, null, null);
        shifts.delete(id);
    }

    /** Sao chép ca sang các ngày chỉ định (không sao chép phân công). */
    @Transactional
    public List<ShiftDetail> cloneTo(String id, List<LocalDate> dates, String actorId) {
        ShiftDetail source = get(id);
        if (dates == null || dates.isEmpty()) {
            throw new IllegalArgumentException("Phải chỉ định ít nhất một ngày để sao chép.");
        }
        List<LocalDate> distinct = new ArrayList<>(new LinkedHashSet<>(dates));
        if (distinct.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Tối đa " + MAX_BATCH + " ngày mỗi lần sao chép.");
        }
        return distinct.stream()
                .map(date -> createOne(source.shift().withDate(date), actorId, false, null, null))
                .toList();
    }

    /** Lặp ca theo thứ trong tuần cho đến ngày until; các ca lặp dùng chung series_id. */
    @Transactional
    public List<ShiftDetail> repeat(String id, RepeatRequest request, String actorId) {
        ShiftDetail source = get(id);
        int every = request.everyNWeeks() == null ? 1 : request.everyNWeeks();
        List<LocalDate> dates = repeatDates(source.shift().date(), request.until(), request.weekdays(), every);
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("Không có ngày nào phù hợp quy tắc lặp.");
        }
        String seriesId = source.seriesId() != null ? source.seriesId() : "series_" + UUID.randomUUID().toString().replace("-", "");
        shifts.setSeries(id, seriesId);
        return dates.stream()
                .map(date -> createOne(source.shift().withDate(date), actorId, false, seriesId, null))
                .toList();
    }

    /** Hủy ca bắt buộc có lý do; hủy các phân công của ca và trả về danh sách nhân viên bị ảnh hưởng. */
    @Transactional
    public CancelResult cancel(String id, int expectedVersion, String reason, String actorId) {
        requireReason(reason);
        ShiftDetail current = get(id);
        if (current.cancelled()) {
            throw new IllegalArgumentException("Ca đã được hủy trước đó.");
        }
        if (current.version() != expectedVersion) {
            throw versionConflict();
        }
        Set<String> affected = new TreeSet<>(shifts.confirmedAssignees(id));
        if (shifts.cancel(id, expectedVersion, actorId, reason.trim()) < 0) {
            throw versionConflict();
        }
        ShiftDetail cancelled = get(id);
        audit("shift_cancel", "Hủy ca " + current.shift().name(),
                affected.isEmpty() ? "Không có phân công bị ảnh hưởng." : "Phân công bị hủy: " + String.join(", ", affected) + ".",
                actorId, current, cancelled, reason.trim());
        return new CancelResult(cancelled, affected);
    }

    /** Các ngày lặp lại, tính từ sau ngày nguồn đến until (gồm cả until). weekdays theo ISO: 1=Thứ hai … 7=Chủ nhật. */
    static List<LocalDate> repeatDates(LocalDate source, LocalDate until, Set<Integer> weekdays, int everyNWeeks) {
        if (until == null || !until.isAfter(source)) {
            throw new IllegalArgumentException("Ngày kết thúc lặp phải sau ngày của ca nguồn.");
        }
        if (ChronoUnit.DAYS.between(source, until) > MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("Khoảng lặp tối đa " + MAX_SPAN_DAYS + " ngày.");
        }
        if (weekdays == null || weekdays.isEmpty() || weekdays.stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
            throw new IllegalArgumentException("Thứ lặp phải thuộc 1 (Thứ hai) đến 7 (Chủ nhật).");
        }
        if (everyNWeeks < 1) {
            throw new IllegalArgumentException("Chu kỳ lặp phải từ 1 tuần trở lên.");
        }
        LocalDate baseWeek = source.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate day = source.plusDays(1); !day.isAfter(until); day = day.plusDays(1)) {
            if (!weekdays.contains(day.getDayOfWeek().getValue())) {
                continue;
            }
            long weeks = ChronoUnit.WEEKS.between(baseWeek, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            if (weeks % everyNWeeks == 0) {
                dates.add(day);
            }
        }
        if (dates.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Quy tắc lặp tạo quá " + MAX_BATCH + " ca.");
        }
        return dates;
    }

    private ShiftDetail createOne(ShiftRequest request, String actorId, boolean emergency, String seriesId, String reason) {
        checkConfig(request);
        checkPeople(null, request, Set.of());
        String id = "shift_" + UUID.randomUUID().toString().replace("-", "");
        shifts.insert(id, request, emergency, seriesId);
        ShiftDetail created = get(id);
        audit(emergency ? "shift_emergency_create" : "shift_create",
                (emergency ? "Bổ sung ca khẩn cấp " : "Tạo ca ") + created.shift().name(), id, actorId, null, created, reason);
        return created;
    }

    /** Lỗi cấu hình và id danh mục/Level/nhân viên không tồn tại. */
    private void checkConfig(ShiftRequest r) {
        List<String> errors = ShiftConfigRules.check(r);
        if (!errors.isEmpty()) {
            throw new ShiftRejectedException(errors, List.of(), List.of());
        }
        Set<String> users = new LinkedHashSet<>(r.mandatoryUserIds());
        users.addAll(r.preferredUserIds());
        missing("Level", "levels", r.levelRequirements().keySet(), errors);
        missing("Nhân viên", "users", users, errors);
        missing("Kỹ năng", "skills", r.skillIds(), errors);
        missing("Chứng chỉ", "certificates", r.certificateIds(), errors);
        missing("Chức vụ", "positions", r.positionIds(), errors);
        if (r.locationId() != null) {
            missing("Địa điểm", "locations", Set.of(r.locationId()), errors);
        }
        if (!errors.isEmpty()) {
            throw new ShiftRejectedException(errors, List.of(), List.of());
        }
    }

    private void missing(String label, String table, java.util.Collection<String> ids, List<String> errors) {
        List<String> absent = shifts.missingIds(table, ids);
        if (!absent.isEmpty()) {
            errors.add(label + " không tồn tại: " + String.join(", ", absent) + ".");
        }
    }

    /**
     * Kiểm tra nhân viên bắt buộc, ưu tiên (và những người đã được phân công khi sửa ca) bằng dịch vụ kiểm tra chung.
     * shiftId null nghĩa là ca mới; khi sửa, phân công hiện có của chính ca này được loại khỏi ngữ cảnh để không
     * bị tính trùng.
     */
    private void checkPeople(String shiftId, ShiftRequest r, Set<String> assignees) {
        Set<String> users = new TreeSet<>(r.mandatoryUserIds());
        users.addAll(r.preferredUserIds());
        users.addAll(assignees);
        if (users.isEmpty()) {
            return;
        }
        String periodId = shifts.periodIdFor(r.date()).orElseThrow(() -> new ShiftRejectedException(
                List.of("Ngày " + r.date() + " không thuộc kỳ nào nên chưa kiểm tra được điều kiện của nhân viên chỉ định."),
                List.of(), List.of()));
        ScheduleData d = data.load(periodId).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ " + periodId + "."));

        Set<String> replaced = d.savedAssignments().stream()
                .filter(a -> a.shiftId().equals(shiftId))
                .map(Assignment::id)
                .collect(Collectors.toSet());
        List<ShiftInfo> context = d.shifts().stream().filter(s -> !s.id().equals(shiftId)).collect(Collectors.toCollection(ArrayList::new));
        ShiftInfo candidateShift = ShiftConfigRules.toShiftInfo(shiftId == null ? "shift_new" : shiftId, r);
        context.add(candidateShift);
        ScheduleContext ctx = new ScheduleContext(d.period(), d.rules(), d.employees(), context, d.availability(),
                d.blockedDates(), d.savedAssignments(), replaced);

        List<Violation> hard = new ArrayList<>();
        List<Violation> soft = new ArrayList<>();
        for (String userId : users) {
            ValidationResult result = validator.validate(ctx, new AssignmentCandidate(userId, candidateShift.id(), null));
            for (Violation v : result.violations()) {
                (v.code().overridable() ? soft : hard).add(v);
            }
        }
        if (!hard.isEmpty()) {
            throw new ShiftRejectedException(List.of(), hard, soft);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Lý do không được để trống.");
        }
    }

    private static ShiftVersionConflictException versionConflict() {
        return new ShiftVersionConflictException("Ca đã được sửa bởi người khác hoặc đã bị hủy. Hãy tải lại rồi thử lại.");
    }

    private void audit(String eventType, String title, String detail, String actorId, Object before, Object after, String reason) {
        shifts.writeHistory(eventType, title, detail, actorId, toJson(before), toJson(after), reason);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không ghi được JSON.", e);
        }
    }
}
