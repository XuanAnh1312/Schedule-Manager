package org.dacnpm.schedulemanager.api.periods;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.BusinessTime;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Vòng đời kỳ đăng ký (TU-01 … TU-03).
 *
 * <pre>
 * draft ──open──► open ──lock / hết hạn──► locked ──archive──► archived
 *   │               ▲                        │
 * delete            └────── reopen ──────────┘
 * </pre>
 *
 * Mọi chuyển trạng thái giữ khóa dòng kỳ, kiểm tra version và ghi {@code history}.
 */
@Profile("sqlserver")
@Service
public class PeriodService {

    private static final Logger log = LoggerFactory.getLogger(PeriodService.class);
    private static final Set<String> CYCLES = Set.of("weekly", "monthly", "yearly");
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_PERIOD_DAYS = 366;
    private static final int MAX_REASON_LENGTH = 1000;

    private final PeriodRepository repository;
    private final AuditTrail audit;
    private final Clock clock;
    private final SchedulePlanGate planGate;

    /**
     * {@code planGate} bắt buộc (TU-R2): thiếu bean thì ứng dụng không khởi động, thay vì âm thầm cho lưu trữ kỳ còn
     * phương án lịch chưa hoàn tất. Bản chạy SQL Server là {@code schedule.plan.PeriodPlanGate}.
     */
    public PeriodService(PeriodRepository repository, AuditTrail audit, Clock clock, SchedulePlanGate planGate) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
        this.planGate = java.util.Objects.requireNonNull(planGate, "SchedulePlanGate");
    }

    /* ── Đọc ─────────────────────────────────────────────────────────────── */

    public List<PeriodView> list(CurrentUser me) {
        List<PeriodRecord> periods = me.admin() ? repository.findAll() : repository.findVisibleForEmployee(me.id());
        return periods.stream().map(p -> view(p, me.admin())).toList();
    }

    public PeriodView get(CurrentUser me, String id) {
        PeriodRecord period = repository.findById(id).orElseThrow(() -> ApiException.notFound("kỳ đăng ký"));
        if (!me.admin() && !visibleToEmployee(period, me.id())) {
            // Không tiết lộ kỳ nháp hoặc kỳ không áp dụng cho nhân viên này.
            throw ApiException.notFound("kỳ đăng ký");
        }
        return view(period, me.admin());
    }

    /* ── Tạo / sửa ───────────────────────────────────────────────────────── */

    @Transactional
    public PeriodView create(CurrentUser me, PeriodRequest request) {
        me.requireAdmin();
        PeriodRecord draft = validate(Ids.next("period"), request, PeriodStatus.DRAFT, 0, null, null);
        repository.insert(draft);
        PeriodRecord saved = load(draft.id());
        audit.record("period.created", "Tạo kỳ đăng ký", saved.name(), me.id(), null, view(saved, true), null);
        return view(saved, true);
    }

    /**
     * Kỳ nháp: sửa mọi trường. Kỳ đang mở: chỉ sửa tên, hạn chót và tự khóa (nhân viên có thể đã
     * đăng ký nên không đổi ngày/đối tượng). Kỳ đã khóa/lưu trữ: không sửa, phải mở lại trước.
     */
    @Transactional
    public PeriodView update(CurrentUser me, String id, PeriodRequest request) {
        me.requireAdmin();
        if (request == null || request.version() == null) {
            throw ApiException.badRequest("VERSION_REQUIRED", "Thiếu version của kỳ (tải lại kỳ trước khi sửa).");
        }
        PeriodRecord current = lockForChange(id, request.version());
        if (current.status() == PeriodStatus.LOCKED || current.status() == PeriodStatus.ARCHIVED) {
            throw ApiException.conflict("PERIOD_NOT_EDITABLE",
                    "Kỳ đã đóng hoặc đã lưu trữ, không thể sửa. Hãy mở lại kỳ nếu cần thay đổi.");
        }
        PeriodRecord next = validate(id, request, current.status(), current.version(),
                current.createdAtUtc(), current.updatedAtUtc());
        if (current.status() == PeriodStatus.OPEN) {
            requireOnlyOpenEditableFieldsChanged(current, next);
            requireDeadlineStillAhead(next);
        }
        if (!repository.update(next, current.version())) {
            throw versionConflict();
        }
        PeriodRecord saved = load(id);
        audit.record("period.updated", "Sửa kỳ đăng ký", saved.name(), me.id(),
                view(current, true), view(saved, true), null);
        return view(saved, true);
    }

    /* ── Chuyển trạng thái ───────────────────────────────────────────────── */

    @Transactional
    public PeriodView open(CurrentUser me, String id, PeriodActionRequest action) {
        me.requireAdmin();
        PeriodRecord current = lockForChange(id, versionOf(action));
        requireStatus(current, PeriodStatus.DRAFT, "Chỉ kỳ nháp mới công bố được.");
        if (current.requiredDaysOfWeek().isEmpty()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Kỳ chưa có ngày trong tuần cần đăng ký.");
        }
        requireDeadlineStillAhead(current);
        return transition(me, current, PeriodStatus.OPEN, current.deadlineUtc(),
                "period.opened", "Công bố kỳ đăng ký", null);
    }

    @Transactional
    public PeriodView lock(CurrentUser me, String id, PeriodActionRequest action) {
        me.requireAdmin();
        PeriodRecord current = lockForChange(id, versionOf(action));
        requireStatus(current, PeriodStatus.OPEN, "Chỉ kỳ đang mở mới đóng được.");
        return transition(me, current, PeriodStatus.LOCKED, current.deadlineUtc(),
                "period.locked", "Đóng đăng ký thủ công", trimToNull(action == null ? null : action.reason()));
    }

    /**
     * Mở lại kỳ đã đóng (TU-03): cần cờ allow_reopen_by_admin, xác nhận, lý do và — khi kỳ tự khóa —
     * hạn mới ở tương lai. Đăng ký đã gửi được giữ nguyên; lịch đã công bố không bị sửa.
     */
    @Transactional
    public PeriodView reopen(CurrentUser me, String id, PeriodActionRequest action) {
        me.requireAdmin();
        if (action == null || !Boolean.TRUE.equals(action.confirm())) {
            throw ApiException.badRequest("CONFIRM_REQUIRED", "Cần xác nhận trước khi mở lại kỳ.");
        }
        String reason = trimToNull(action.reason());
        if (reason == null) {
            throw ApiException.badRequest("REASON_REQUIRED", "Phải nhập lý do mở lại kỳ.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("REASON_TOO_LONG", "Lý do tối đa " + MAX_REASON_LENGTH + " ký tự.");
        }
        PeriodRecord current = lockForChange(id, action.version());
        requireStatus(current, PeriodStatus.LOCKED, "Chỉ kỳ đã đóng mới mở lại được.");
        if (!current.allowReopenByAdmin()) {
            throw ApiException.conflict("REOPEN_NOT_ALLOWED", "Kỳ này được cấu hình không cho phép mở lại.");
        }
        LocalDateTime newDeadlineUtc = action.newDeadline() == null ? null
                : BusinessTime.toUtc(action.newDeadline().truncatedTo(ChronoUnit.MINUTES), zone());
        if (current.autoLock() && newDeadlineUtc == null) {
            throw ApiException.badRequest("NEW_DEADLINE_REQUIRED",
                    "Kỳ tự khóa khi hết hạn nên phải đặt hạn chót mới khi mở lại.");
        }
        if (newDeadlineUtc != null && !newDeadlineUtc.isAfter(BusinessTime.nowUtc(clock))) {
            throw ApiException.badRequest("NEW_DEADLINE_IN_PAST", "Hạn chót mới phải ở tương lai.");
        }
        if (newDeadlineUtc != null && current.openAtUtc() != null && !newDeadlineUtc.isAfter(current.openAtUtc())) {
            throw ApiException.badRequest("NEW_DEADLINE_BEFORE_OPEN", "Hạn chót mới phải sau thời điểm mở đăng ký.");
        }
        LocalDateTime deadlineUtc = newDeadlineUtc != null ? newDeadlineUtc : current.deadlineUtc();
        return transition(me, current, PeriodStatus.OPEN, deadlineUtc, "period.reopened", "Mở lại kỳ đăng ký", reason);
    }

    @Transactional
    public PeriodView archive(CurrentUser me, String id, PeriodActionRequest action) {
        me.requireAdmin();
        PeriodRecord current = lockForChange(id, versionOf(action));
        requireStatus(current, PeriodStatus.LOCKED, "Chỉ kỳ đã đóng mới lưu trữ được.");
        List<String> unfinished = planGate.unfinishedPlans(id);
        if (!unfinished.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_FINALIZED",
                    "Kỳ còn phương án lịch chưa hoàn tất. Hãy công bố hoặc hủy phương án trước khi lưu trữ.",
                    unfinished);
        }
        return transition(me, current, PeriodStatus.ARCHIVED, current.deadlineUtc(),
                "period.archived", "Lưu trữ kỳ đăng ký", trimToNull(action == null ? null : action.reason()));
    }

    /** Chỉ xóa cứng kỳ nháp; kỳ đã công bố phải giữ lịch sử (mục 7.5). */
    @Transactional
    public void delete(CurrentUser me, String id) {
        me.requireAdmin();
        PeriodRecord current = repository.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("kỳ đăng ký"));
        requireStatus(current, PeriodStatus.DRAFT, "Chỉ xóa được kỳ nháp. Kỳ đã công bố hãy đóng hoặc lưu trữ.");
        if (repository.countRegistrations(id) > 0) {
            throw ApiException.conflict("PERIOD_HAS_REGISTRATIONS", "Kỳ đã có đăng ký, không thể xóa.");
        }
        repository.delete(id);
        audit.record("period.deleted", "Xóa kỳ nháp", current.name(), me.id(), view(current, true), null, null);
    }

    /**
     * Tác vụ nền: khóa các kỳ tự khóa đã qua hạn. Chỉ để trạng thái hiển thị đúng — API đăng ký
     * vẫn tự chặn theo {@link RegistrationWindow} kể cả khi tác vụ này chưa chạy.
     */
    @Transactional
    public int autoLockExpired() {
        List<PeriodRepository.AutoLocked> locked = repository.lockExpired(BusinessTime.nowUtc(clock));
        for (PeriodRepository.AutoLocked p : locked) {
            audit.record("period.auto_locked", "Tự động đóng đăng ký khi hết hạn", p.name(), null,
                    Map.of("periodId", p.id(), "status", "open"),
                    Map.of("periodId", p.id(), "status", "locked",
                            "deadline", BusinessTime.label(p.deadlineUtc(), zone())),
                    null);
        }
        if (!locked.isEmpty()) {
            log.info("Auto-locked {} registration period(s) after deadline.", locked.size());
        }
        return locked.size();
    }

    /* ── Hỗ trợ ──────────────────────────────────────────────────────────── */

    private PeriodView transition(CurrentUser me, PeriodRecord current, PeriodStatus target, LocalDateTime deadlineUtc,
                                  String eventType, String title, String reason) {
        if (!repository.updateStatus(current.id(), target, deadlineUtc, current.version())) {
            throw versionConflict();
        }
        PeriodRecord saved = load(current.id());
        audit.record(eventType, title, saved.name(), me.id(), view(current, true), view(saved, true), reason);
        return view(saved, true);
    }

    private PeriodRecord lockForChange(String id, Integer expectedVersion) {
        PeriodRecord current = repository.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("kỳ đăng ký"));
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw versionConflict();
        }
        return current;
    }

    private PeriodRecord load(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("kỳ đăng ký"));
    }

    private boolean visibleToEmployee(PeriodRecord period, String userId) {
        return period.status() != PeriodStatus.DRAFT
                && (period.applicableUserIds().isEmpty() || period.applicableUserIds().contains(userId));
    }

    private PeriodView view(PeriodRecord period, boolean forAdmin) {
        return PeriodView.of(period, clock.instant(), zone(), forAdmin);
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    private static Integer versionOf(PeriodActionRequest action) {
        return action == null ? null : action.version();
    }

    private static void requireStatus(PeriodRecord period, PeriodStatus expected, String message) {
        if (period.status() != expected) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    message + " Trạng thái hiện tại: " + period.status().dbValue() + ".");
        }
    }

    private void requireDeadlineStillAhead(PeriodRecord period) {
        if (period.autoLock()) {
            if (period.deadlineUtc() == null) {
                throw ApiException.badRequest("DEADLINE_REQUIRED", "Kỳ tự khóa khi hết hạn phải có hạn chót.");
            }
            if (!period.deadlineUtc().isAfter(BusinessTime.nowUtc(clock))) {
                throw ApiException.badRequest("DEADLINE_IN_PAST",
                        "Hạn chót đã qua. Hãy đặt hạn chót ở tương lai hoặc dùng thao tác đóng kỳ.");
            }
        }
    }

    private static void requireOnlyOpenEditableFieldsChanged(PeriodRecord current, PeriodRecord next) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(current.cycle(), next.cycle())) changed.add("cycle");
        if (!Objects.equals(current.startDate(), next.startDate())) changed.add("startDate");
        if (!Objects.equals(current.endDate(), next.endDate())) changed.add("endDate");
        if (!Objects.equals(current.openAtUtc(), next.openAtUtc())) changed.add("openAt");
        if (current.allowReopenByAdmin() != next.allowReopenByAdmin()) changed.add("allowReopenByAdmin");
        if (!current.requiredDaysOfWeek().equals(next.requiredDaysOfWeek())) changed.add("requiredDaysOfWeek");
        if (!current.exemptDates().equals(next.exemptDates())) changed.add("exemptDates");
        if (!current.closedDates().equals(next.closedDates())) changed.add("closedDates");
        if (!current.applicableUserIds().equals(next.applicableUserIds())) changed.add("applicableUserIds");
        if (!changed.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "FIELD_LOCKED_WHILE_OPEN",
                    "Kỳ đang mở chỉ được sửa tên, hạn chót và chế độ tự khóa.", changed);
        }
    }

    /** Kiểm tra và chuẩn hóa body; ném 400 kèm danh sách lỗi. */
    private PeriodRecord validate(String id, PeriodRequest r, PeriodStatus status, int version,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        if (r == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Thiếu dữ liệu kỳ đăng ký.");
        }
        List<String> errors = new ArrayList<>();
        String name = trimToNull(r.name());
        if (name == null) errors.add("name: bắt buộc.");
        else if (name.length() > MAX_NAME_LENGTH) errors.add("name: tối đa " + MAX_NAME_LENGTH + " ký tự.");

        String cycle = trimToNull(r.cycle());
        if (cycle == null || !CYCLES.contains(cycle)) errors.add("cycle: phải là weekly, monthly hoặc yearly.");

        LocalDate start = r.startDate();
        LocalDate end = r.endDate();
        if (start == null) errors.add("startDate: bắt buộc.");
        if (end == null) errors.add("endDate: bắt buộc.");
        if (start != null && end != null) {
            if (end.isBefore(start)) errors.add("endDate: phải bằng hoặc sau startDate.");
            else if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_PERIOD_DAYS)
                errors.add("endDate: kỳ dài tối đa " + MAX_PERIOD_DAYS + " ngày.");
        }

        LocalDateTime openAtUtc = r.openAt() == null ? null
                : BusinessTime.toUtc(r.openAt().truncatedTo(ChronoUnit.MINUTES), zone());
        LocalDateTime deadlineUtc = r.deadline() == null ? null
                : BusinessTime.toUtc(r.deadline().truncatedTo(ChronoUnit.MINUTES), zone());
        if (openAtUtc != null && deadlineUtc != null && !deadlineUtc.isAfter(openAtUtc)) {
            errors.add("deadline: phải sau openAt.");
        }
        boolean autoLock = r.autoLock() == null || r.autoLock();
        boolean allowReopen = r.allowReopenByAdmin() == null || r.allowReopenByAdmin();

        List<Integer> weekdays = new ArrayList<>(new TreeSet<>(r.requiredDaysOfWeek() == null
                ? List.of() : r.requiredDaysOfWeek().stream().filter(Objects::nonNull).toList()));
        if (weekdays.isEmpty()) errors.add("requiredDaysOfWeek: chọn ít nhất một thứ (0 = Chủ nhật … 6 = Thứ bảy).");
        if (weekdays.stream().anyMatch(d -> d < 0 || d > 6)) errors.add("requiredDaysOfWeek: giá trị từ 0 đến 6.");

        List<LocalDate> exempt = sortedDates(r.exemptDates());
        List<LocalDate> closed = sortedDates(r.closedDates());
        if (start != null && end != null) {
            if (exempt.stream().anyMatch(d -> d.isBefore(start) || d.isAfter(end)))
                errors.add("exemptDates: phải nằm trong kỳ.");
            if (closed.stream().anyMatch(d -> d.isBefore(start) || d.isAfter(end)))
                errors.add("closedDates: phải nằm trong kỳ.");
        }
        if (exempt.stream().anyMatch(closed::contains)) errors.add("exemptDates/closedDates: một ngày không thể vừa miễn vừa đóng.");

        List<String> applicable = new ArrayList<>(new LinkedHashSet<>(r.applicableUserIds() == null
                ? List.of() : r.applicableUserIds().stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).toList()));
        applicable.sort(String::compareTo);
        if (!applicable.isEmpty()) {
            List<String> invalid = repository.findInvalidEmployeeIds(applicable);
            if (!invalid.isEmpty()) errors.add("applicableUserIds: không phải nhân viên đang hoạt động: " + invalid);
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu kỳ đăng ký không hợp lệ.", errors);
        }
        return new PeriodRecord(id, name, cycle, start, end, openAtUtc, deadlineUtc, autoLock, allowReopen,
                status, version, createdAt, updatedAt, weekdays, exempt, closed, applicable);
    }

    private static List<LocalDate> sortedDates(List<LocalDate> dates) {
        return dates == null ? List.of() : new ArrayList<>(new TreeSet<>(dates.stream().filter(Objects::nonNull).toList()));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException versionConflict() {
        return ApiException.conflict("VERSION_CONFLICT", "Kỳ đã được người khác thay đổi. Hãy tải lại rồi thử lại.");
    }
}
