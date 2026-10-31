package org.dacnpm.schedulemanager.api.periods;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.BusinessTime;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Vòng đời kỳ (TU-01 … TU-03, AC-06): quyền, chuyển trạng thái hợp lệ, mở lại, audit. */
class PeriodServiceTest {

    private static final CurrentUser ADMIN = new CurrentUser("usr_admin_001", true);
    private static final CurrentUser EMPLOYEE = new CurrentUser("nv001", false);

    private PeriodRepository repository;
    private AuditTrail audit;
    private MutableClock clock;
    private PeriodService service;

    @BeforeEach
    void setUp() {
        repository = mock(PeriodRepository.class);
        audit = mock(AuditTrail.class);
        clock = MutableClock.atVn(LocalDateTime.of(2026, 10, 29, 10, 0));
        service = new PeriodService(repository, audit, clock, periodId -> List.of());
    }

    /* ── Quyền ───────────────────────────────────────────────────────────── */

    @Test
    void employee_cannotCreateOrReopen() {
        assertStatus(HttpStatus.FORBIDDEN, () -> service.create(EMPLOYEE, request(null)));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.reopen(EMPLOYEE, "p1",
                new PeriodActionRequest(null, true, "lý do", LocalDateTime.of(2026, 11, 5, 23, 59))));
        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void employee_cannotSeeDraftPeriod() {
        when(repository.findById("p1")).thenReturn(Optional.of(period(PeriodStatus.DRAFT, true, true)));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(EMPLOYEE, "p1"));
    }

    /* ── Tạo / sửa ───────────────────────────────────────────────────────── */

    @Test
    void create_validatesInput_andCollectsErrors() {
        PeriodRequest bad = new PeriodRequest(" ", "daily", LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 1),
                null, null, true, true, List.of(7), List.of(), List.of(), List.of(), null);
        ApiException ex = assertThrows(ApiException.class, () -> service.create(ADMIN, bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(4, ex.getDetails().size(), ex.getDetails().toString());
        verify(repository, never()).insert(any());
    }

    @Test
    void create_storesDraft_andDeadlineInUtc() {
        when(repository.findById(any())).thenAnswer(inv -> Optional.of(period(PeriodStatus.DRAFT, true, true)));
        service.create(ADMIN, request(LocalDateTime.of(2026, 10, 31, 23, 59)));
        verify(repository).insert(org.mockito.ArgumentMatchers.argThat(p ->
                p.status() == PeriodStatus.DRAFT
                        && p.deadlineUtc().equals(LocalDateTime.of(2026, 10, 31, 16, 59))));
        verify(audit).record(eq("period.created"), any(), any(), eq(ADMIN.id()), isNull(), any(), isNull());
    }

    @Test
    void update_openPeriod_cannotChangeDates() {
        PeriodRecord open = period(PeriodStatus.OPEN, true, true);
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(open));
        PeriodRequest changedDates = new PeriodRequest(open.name(), open.cycle(), open.startDate().plusDays(1),
                open.endDate(), null, LocalDateTime.of(2026, 11, 5, 23, 59), true, true, open.requiredDaysOfWeek(),
                List.of(), List.of(), List.of(), open.version());
        ApiException ex = assertThrows(ApiException.class, () -> service.update(ADMIN, "p1", changedDates));
        assertEquals("FIELD_LOCKED_WHILE_OPEN", ex.getCode());
        verify(repository, never()).update(any(), anyInt());
    }

    @Test
    void update_lockedPeriod_isRejected() {
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(period(PeriodStatus.LOCKED, true, true)));
        ApiException ex = assertThrows(ApiException.class, () -> service.update(ADMIN, "p1", request(null, 3)));
        assertEquals("PERIOD_NOT_EDITABLE", ex.getCode());
    }

    @Test
    void update_staleVersion_isConflict() {
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(period(PeriodStatus.DRAFT, true, true)));
        ApiException ex = assertThrows(ApiException.class, () -> service.update(ADMIN, "p1", request(null, 99)));
        assertEquals("VERSION_CONFLICT", ex.getCode());
    }

    /* ── Chuyển trạng thái ───────────────────────────────────────────────── */

    @Test
    void open_withPastDeadline_isRejected() {
        PeriodRecord draft = withDeadline(period(PeriodStatus.DRAFT, true, true), LocalDateTime.of(2026, 10, 28, 23, 59));
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(draft));
        ApiException ex = assertThrows(ApiException.class, () -> service.open(ADMIN, "p1", null));
        assertEquals("DEADLINE_IN_PAST", ex.getCode());
    }

    @Test
    void invalidTransition_isConflict() {
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(period(PeriodStatus.OPEN, true, true)));
        assertEquals("INVALID_TRANSITION",
                assertThrows(ApiException.class, () -> service.archive(ADMIN, "p1", null)).getCode());
        assertEquals("INVALID_TRANSITION",
                assertThrows(ApiException.class, () -> service.open(ADMIN, "p1", null)).getCode());
    }

    @Test
    void delete_onlyDraft() {
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(period(PeriodStatus.LOCKED, true, true)));
        assertStatus(HttpStatus.CONFLICT, () -> service.delete(ADMIN, "p1"));
        verify(repository, never()).delete(any());
    }

    /* ── Lưu trữ và phương án lịch ───────────────────────────────────────── */

    @Test
    void archive_blockedWhileSchedulePlansAreUnfinished() {
        PeriodRecord locked = period(PeriodStatus.LOCKED, true, true);
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(locked));
        PeriodService withPlans = new PeriodService(repository, audit, clock,
                periodId -> List.of("plan_1 — pending_approval"));

        ApiException ex = assertThrows(ApiException.class, () -> withPlans.archive(ADMIN, "p1", null));

        assertEquals("PLAN_NOT_FINALIZED", ex.getCode());
        assertEquals(List.of("plan_1 — pending_approval"), ex.getDetails());
        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void archive_allowedWhenNoUnfinishedPlans() {
        PeriodRecord locked = period(PeriodStatus.LOCKED, true, true);
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(locked));
        when(repository.updateStatus("p1", PeriodStatus.ARCHIVED, locked.deadlineUtc(), locked.version())).thenReturn(true);
        when(repository.findById("p1")).thenReturn(Optional.of(locked.withStatus(PeriodStatus.ARCHIVED, locked.deadlineUtc())));

        service.archive(ADMIN, "p1", null);

        verify(repository).updateStatus("p1", PeriodStatus.ARCHIVED, locked.deadlineUtc(), locked.version());
    }

    @Test
    void archive_locksThePeriodRowBeforeAskingTheScheduleGate() {
        PeriodRecord locked = period(PeriodStatus.LOCKED, true, true);
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(locked));
        SchedulePlanGate gate = org.mockito.Mockito.mock(SchedulePlanGate.class);
        when(gate.unfinishedPlans("p1")).thenReturn(List.of("plan_2 — approved"));

        assertThrows(ApiException.class, () -> new PeriodService(repository, audit, clock, gate).archive(ADMIN, "p1", null));

        var order = org.mockito.Mockito.inOrder(repository, gate);
        order.verify(repository).findByIdForUpdate("p1");
        order.verify(gate).unfinishedPlans("p1");
    }

    @Test
    void periodService_cannotBeBuiltWithoutAScheduleGate() {
        assertThrows(NullPointerException.class, () -> new PeriodService(repository, audit, clock, null));
    }

    /* ── Mở lại (AC-06) ──────────────────────────────────────────────────── */

    @Test
    void reopen_requiresConfirmReasonAndFutureDeadline() {
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(period(PeriodStatus.LOCKED, true, true)));
        LocalDateTime future = LocalDateTime.of(2026, 11, 2, 23, 59);
        assertEquals("CONFIRM_REQUIRED", code(() -> service.reopen(ADMIN, "p1", new PeriodActionRequest(null, false, "x", future))));
        assertEquals("REASON_REQUIRED", code(() -> service.reopen(ADMIN, "p1", new PeriodActionRequest(null, true, "  ", future))));
        assertEquals("NEW_DEADLINE_REQUIRED", code(() -> service.reopen(ADMIN, "p1", new PeriodActionRequest(null, true, "x", null))));
        assertEquals("NEW_DEADLINE_IN_PAST", code(() -> service.reopen(ADMIN, "p1",
                new PeriodActionRequest(null, true, "x", LocalDateTime.of(2026, 10, 29, 9, 0)))));
        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void reopen_notAllowedByPeriodConfig() {
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(period(PeriodStatus.LOCKED, true, false)));
        assertEquals("REOPEN_NOT_ALLOWED", code(() -> service.reopen(ADMIN, "p1",
                new PeriodActionRequest(null, true, "x", LocalDateTime.of(2026, 11, 2, 23, 59)))));
    }

    @Test
    void reopen_success_setsNewDeadline_andAuditsReason() {
        PeriodRecord locked = period(PeriodStatus.LOCKED, true, true);
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(locked));
        when(repository.updateStatus(eq("p1"), eq(PeriodStatus.OPEN), any(), eq(locked.version()))).thenReturn(true);
        when(repository.findById("p1")).thenReturn(Optional.of(locked.withStatus(PeriodStatus.OPEN,
                LocalDateTime.of(2026, 11, 2, 16, 59))));

        PeriodView view = service.reopen(ADMIN, "p1",
                new PeriodActionRequest(locked.version(), true, "Bổ sung nhân viên mới", LocalDateTime.of(2026, 11, 2, 23, 59)));

        assertEquals("open", view.status());
        verify(repository).updateStatus("p1", PeriodStatus.OPEN, LocalDateTime.of(2026, 11, 2, 16, 59), locked.version());
        verify(audit).record(eq("period.reopened"), any(), any(), eq(ADMIN.id()), any(), any(), eq("Bổ sung nhân viên mới"));
    }

    @Test
    void autoLock_auditsEachLockedPeriodAsSystem() {
        when(repository.lockExpired(any())).thenReturn(List.of(
                new PeriodRepository.AutoLocked("p1", "Kỳ 10", LocalDateTime.of(2026, 10, 5, 16, 59))));
        assertEquals(1, service.autoLockExpired());
        verify(repository).lockExpired(BusinessTime.nowUtc(clock));
        verify(audit).record(eq("period.auto_locked"), any(), any(), isNull(), any(), any(), isNull());
    }

    /* ── AC-04: deadline enforcement ─────────────────────────────────────── */

    /**
     * AC-04 / AC-05: Kỳ đang mở và đã qua hạn — mọi lần cập nhật đều bị từ chối vì server kiểm tra
     * thời điểm thật, không tin thời điểm client gửi (kể cả form đã mở từ trước hạn).
     */
    @Test
    void update_openPeriod_withExpiredDeadline_isRejected() {
        // Đồng hồ server: 29/10/2026 10:00; deadline kỳ đã qua: 28/10/2026 23:59
        PeriodRecord openExpired = withDeadline(period(PeriodStatus.OPEN, true, true),
                LocalDateTime.of(2026, 10, 28, 23, 59));
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(openExpired));

        // Người dùng đã mở form trước khi hạn, nhưng gửi lúc server đã qua hạn
        PeriodRequest lateSubmit = new PeriodRequest(openExpired.name(), openExpired.cycle(),
                openExpired.startDate(), openExpired.endDate(), null,
                LocalDateTime.of(2026, 10, 28, 23, 59), // deadline cũ
                true, true, openExpired.requiredDaysOfWeek(),
                List.of(), List.of(), List.of(), openExpired.version());

        ApiException ex = assertThrows(ApiException.class, () -> service.update(ADMIN, "p1", lateSubmit));
        // Kỳ đang mở với autoLock=true và deadline đã qua → phải báo lỗi (không cho phép sửa deadline đã cũ)
        assertEquals("DEADLINE_IN_PAST", ex.getCode());
        verify(repository, never()).update(any(), anyInt());
    }

    /**
     * AC-04: autoLock=false — kỳ KHÔNG tự khóa khi qua hạn, nhưng vẫn có thể update tên/deadline
     * mà không bị chặn bởi requireDeadlineStillAhead (vì autoLock=false bỏ qua kiểm tra hạn).
     */
    @Test
    void update_openPeriod_autoLockFalse_allowsUpdateEvenAfterDeadline() {
        // Kỳ đang mở, autoLock=false, deadline đã qua
        PeriodRecord openNoAutoLock = withDeadline(period(PeriodStatus.OPEN, false, true),
                LocalDateTime.of(2026, 10, 28, 23, 59));
        when(repository.findByIdForUpdate("p1")).thenReturn(Optional.of(openNoAutoLock));
        when(repository.update(any(), anyInt())).thenReturn(true);
        when(repository.findById("p1")).thenReturn(Optional.of(openNoAutoLock));

        // Chỉ sửa tên — được phép khi autoLock=false và deadline đã qua
        PeriodRequest renameOnly = new PeriodRequest("Tên mới", openNoAutoLock.cycle(),
                openNoAutoLock.startDate(), openNoAutoLock.endDate(), null,
                openNoAutoLock.deadlineUtc() == null ? null
                        : openNoAutoLock.deadlineUtc().plusHours(7), // đổi về local VN để validate
                false, true, openNoAutoLock.requiredDaysOfWeek(),
                List.of(), List.of(), List.of(), openNoAutoLock.version());

        // Không ném ngoại lệ — autoLock=false bỏ qua kiểm tra deadline quá hạn
        service.update(ADMIN, "p1", renameOnly);
        verify(repository).update(any(), eq(openNoAutoLock.version()));
    }

    /**
     * AC-04: autoLock job — kỳ với autoLock=false sẽ KHÔNG bị đưa vào danh sách tự khóa
     * (DB query lockExpired chỉ chọn kỳ có auto_lock=true; test này xác nhận service không tự khóa
     * kỳ nào khi repository trả danh sách trống).
     */
    @Test
    void autoLock_doesNotLockPeriodsWithAutoLockFalse() {
        // Repository trả rỗng vì không có kỳ nào có auto_lock=true quá hạn
        when(repository.lockExpired(any())).thenReturn(List.of());

        assertEquals(0, service.autoLockExpired());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    /* ── Hỗ trợ ──────────────────────────────────────────────────────────── */

    private static PeriodRequest request(LocalDateTime deadline) {
        return request(deadline, null);
    }

    private static PeriodRequest request(LocalDateTime deadline, Integer version) {
        return new PeriodRequest("Kỳ tháng 11/2026", "monthly", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                null, deadline, true, true, List.of(1, 2, 3, 4, 5, 6), List.of(), List.of(), List.of(), version);
    }

    private static PeriodRecord period(PeriodStatus status, boolean autoLock, boolean allowReopen) {
        return new PeriodRecord("p1", "Kỳ tháng 11/2026", "monthly", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), null, LocalDateTime.of(2026, 10, 31, 16, 59), autoLock, allowReopen,
                status, 3, null, null, List.of(1, 2, 3, 4, 5, 6), List.of(), List.of(), List.of());
    }

    private static PeriodRecord withDeadline(PeriodRecord p, LocalDateTime deadlineVn) {
        return new PeriodRecord(p.id(), p.name(), p.cycle(), p.startDate(), p.endDate(), p.openAtUtc(),
                BusinessTime.toUtc(deadlineVn, MutableClock.VN), p.autoLock(), p.allowReopenByAdmin(), p.status(),
                p.version(), null, null, p.requiredDaysOfWeek(), p.exemptDates(), p.closedDates(), p.applicableUserIds());
    }

    private static String code(Runnable call) {
        return assertThrows(ApiException.class, call::run).getCode();
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        assertEquals(expected, assertThrows(ApiException.class, call::run).getStatus());
    }
}
