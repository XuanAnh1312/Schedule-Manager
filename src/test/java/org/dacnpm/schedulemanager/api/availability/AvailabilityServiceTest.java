package org.dacnpm.schedulemanager.api.availability;

import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Entry;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.SaveRequest;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Slot;
import org.dacnpm.schedulemanager.api.periods.PeriodRecord;
import org.dacnpm.schedulemanager.api.periods.PeriodRepository;
import org.dacnpm.schedulemanager.api.periods.PeriodStatus;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.BusinessTime;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Đăng ký thời gian rảnh (TU-04) và hạn chót ở backend (AC-04, AC-05). */
class AvailabilityServiceTest {

    private static final CurrentUser EMPLOYEE = new CurrentUser("nv001", false);
    private static final CurrentUser ADMIN = new CurrentUser("usr_admin_001", true);
    /** Hạn chót 05/10/2026 23:59 giờ Việt Nam (ví dụ trong tài liệu chức năng mục 2.2). */
    private static final LocalDateTime DEADLINE_VN = LocalDateTime.of(2026, 10, 5, 23, 59);

    private AvailabilityRepository repository;
    private PeriodRepository periods;
    private AuditTrail audit;
    private MutableClock clock;
    private AvailabilityService service;

    @BeforeEach
    void setUp() {
        repository = mock(AvailabilityRepository.class);
        periods = mock(PeriodRepository.class);
        audit = mock(AuditTrail.class);
        clock = MutableClock.atVn(LocalDateTime.of(2026, 10, 3, 9, 0));
        service = new AvailabilityService(repository, periods, audit, clock);
        when(periods.isApplicableActiveEmployee(anyString(), anyString())).thenReturn(true);
        when(repository.find(anyString(), anyString())).thenReturn(Optional.empty());
    }

    /* ── AC-04: hết hạn + tự khóa → từ chối tạo/sửa/lưu nháp/gửi ───────────── */

    @Test
    void afterDeadline_withAutoLock_saveAndSubmitAreRejected_andNothingIsWritten() {
        givenPeriod(PeriodStatus.OPEN, true); // job tự khóa chưa chạy, status vẫn open
        clock.setVn(DEADLINE_VN.plusMinutes(1));

        ApiException save = assertThrows(ApiException.class, () -> service.saveMine(EMPLOYEE, "p1", oneDay()));
        ApiException submit = assertThrows(ApiException.class, () -> service.submitMine(EMPLOYEE, "p1", oneDay()));

        assertEquals(HttpStatus.CONFLICT, save.getStatus());
        assertEquals("REGISTRATION_CLOSED", submit.getCode());
        assertEquals("Đã đóng đăng ký — hạn chót 05/10/2026 23:59", submit.getMessage());
        verify(repository, never()).insert(any(), any(), any(), any(), any());
        verify(repository, never()).updateStatus(any(), any(), any());
        verify(repository, never()).replaceEntries(any(), any());
    }

    @Test
    void exactlyAtDeadline_isAlreadyClosed() {
        givenPeriod(PeriodStatus.OPEN, true);
        clock.setVn(DEADLINE_VN);
        assertEquals("REGISTRATION_CLOSED",
                assertThrows(ApiException.class, () -> service.submitMine(EMPLOYEE, "p1", oneDay())).getCode());
    }

    @Test
    void manualClose_afterDeadline_isStillAccepted() {
        givenPeriod(PeriodStatus.OPEN, false);
        clock.setVn(DEADLINE_VN.plusDays(2));
        service.submitMine(EMPLOYEE, "p1", oneDay());
        verify(repository).insert(anyString(), eq("nv001"), eq("p1"), eq("submitted"), any());
    }

    @Test
    void lockedPeriod_isRejectedEvenWithManualClose() {
        givenPeriod(PeriodStatus.LOCKED, false);
        assertEquals("REGISTRATION_CLOSED",
                assertThrows(ApiException.class, () -> service.saveMine(EMPLOYEE, "p1", oneDay())).getCode());
    }

    /* ── AC-05: form mở trước hạn, gửi sau hạn ──────────────────────────── */

    @Test
    void formOpenedBeforeDeadline_submittedAfter_isRejectedByServerTime() {
        givenPeriod(PeriodStatus.OPEN, true);
        clock.setVn(DEADLINE_VN.minusMinutes(5));
        var opened = service.getMine(EMPLOYEE, "p1");
        assertTrue(opened.editable(), "Form vẫn sửa được khi mở trước hạn");

        clock.advance(Duration.ofMinutes(10)); // người dùng bấm gửi sau hạn
        ApiException ex = assertThrows(ApiException.class, () -> service.submitMine(EMPLOYEE, "p1", oneDay()));
        assertEquals("REGISTRATION_CLOSED", ex.getCode());
        verify(repository, never()).replaceEntries(any(), any());
    }

    @Test
    void beforeOpenAt_isNotOpenYet() {
        givenPeriod(PeriodStatus.OPEN, true, LocalDateTime.of(2026, 10, 4, 8, 0));
        assertEquals("REGISTRATION_NOT_OPEN",
                assertThrows(ApiException.class, () -> service.saveMine(EMPLOYEE, "p1", oneDay())).getCode());
    }

    /* ── Quyền và đối tượng áp dụng ──────────────────────────────────────── */

    @Test
    void employeeOutsideApplicableList_isForbidden() {
        givenPeriod(PeriodStatus.OPEN, true);
        when(periods.isApplicableActiveEmployee("p1", "nv001")).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> service.saveMine(EMPLOYEE, "p1", oneDay())).getStatus());
    }

    @Test
    void admin_cannotRegisterAvailability() {
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> service.saveMine(ADMIN, "p1", oneDay())).getStatus());
    }

    @Test
    void draftPeriod_looksNotFoundToEmployee() {
        givenPeriod(PeriodStatus.DRAFT, true);
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.getMine(EMPLOYEE, "p1")).getStatus());
    }

    /* ── Kiểm tra dữ liệu ────────────────────────────────────────────────── */

    @Test
    void validation_rejectsOutOfPeriod_exemptClosed_nonRequiredDay_overlapAndReversedSlots() {
        PeriodRecord p = givenPeriod(PeriodStatus.OPEN, true);
        SaveRequest bad = new SaveRequest(List.of(
                day(LocalDate.of(2026, 11, 1), slot(8, 12)),                    // ngoài kỳ
                day(LocalDate.of(2026, 10, 10), slot(8, 12)),                   // ngày miễn
                day(LocalDate.of(2026, 10, 11), slot(8, 12)),                   // ngày đóng (Chủ nhật)
                day(LocalDate.of(2026, 10, 4), slot(8, 12)),                    // Chủ nhật: không cần đăng ký
                new Entry(LocalDate.of(2026, 10, 6), List.of(slot(8, 12), slot(11, 14))), // chồng giờ
                day(LocalDate.of(2026, 10, 7), new Slot(LocalTime.of(17, 0), LocalTime.of(9, 0), null)),
                day(LocalDate.of(2026, 10, 8), new Slot(LocalTime.of(8, 0), LocalTime.of(9, 0), "maybe"))));
        ApiException ex = assertThrows(ApiException.class, () -> service.validateEntries(p, bad));
        assertEquals(7, ex.getDetails().size(), ex.getDetails().toString());
    }

    @Test
    void validation_acceptsAvailableAndUnavailable_andSkipsEmptyDays() {
        PeriodRecord p = givenPeriod(PeriodStatus.OPEN, true);
        List<Entry> entries = service.validateEntries(p, new SaveRequest(List.of(
                new Entry(LocalDate.of(2026, 10, 6), List.of(slot(13, 17), new Slot(LocalTime.of(7, 0),
                        LocalTime.of(12, 0), "unavailable"))),
                new Entry(LocalDate.of(2026, 10, 7), List.of()))));
        assertEquals(1, entries.size());
        assertEquals("unavailable", entries.get(0).slots().get(0).type(), "sorted by start time");
    }

    @Test
    void submit_withoutAnyDay_isRejected() {
        givenPeriod(PeriodStatus.OPEN, true);
        assertEquals("EMPTY_REGISTRATION", assertThrows(ApiException.class,
                () -> service.submitMine(EMPLOYEE, "p1", new SaveRequest(List.of()))).getCode());
    }

    @Test
    void submit_success_storesSubmittedAndAudits() {
        givenPeriod(PeriodStatus.OPEN, true);
        service.submitMine(EMPLOYEE, "p1", oneDay());
        verify(repository).insert(anyString(), eq("nv001"), eq("p1"), eq("submitted"),
                eq(BusinessTime.nowUtc(clock)));
        verify(repository).replaceEntries(anyString(), any());
        verify(audit).record(eq("availability.submitted"), any(), any(), eq("nv001"), any(), any(), any());
    }

    @Test
    void wholeDay_counts24Hours() {
        assertEquals(24 * 60, AvailabilityService.minutes(LocalTime.MIDNIGHT, LocalTime.of(23, 59)));
    }

    /* ── Hỗ trợ ──────────────────────────────────────────────────────────── */

    private PeriodRecord givenPeriod(PeriodStatus status, boolean autoLock) {
        return givenPeriod(status, autoLock, LocalDateTime.of(2026, 9, 20, 8, 0));
    }

    private PeriodRecord givenPeriod(PeriodStatus status, boolean autoLock, LocalDateTime openAtVn) {
        PeriodRecord p = new PeriodRecord("p1", "Kỳ tháng 10/2026", "monthly", LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), BusinessTime.toUtc(openAtVn, MutableClock.VN),
                BusinessTime.toUtc(DEADLINE_VN, MutableClock.VN), autoLock, true, status, 1, null, null,
                List.of(1, 2, 3, 4, 5, 6), List.of(LocalDate.of(2026, 10, 10)), List.of(LocalDate.of(2026, 10, 11)),
                List.of());
        when(periods.findById("p1")).thenReturn(Optional.of(p));
        when(periods.findByIdForUpdate("p1")).thenReturn(Optional.of(p));
        return p;
    }

    private static SaveRequest oneDay() {
        return new SaveRequest(List.of(day(LocalDate.of(2026, 10, 6), slot(7, 15))));
    }

    private static Entry day(LocalDate date, Slot slot) {
        return new Entry(date, List.of(slot));
    }

    private static Slot slot(int from, int to) {
        return new Slot(LocalTime.of(from, 0), LocalTime.of(to, 0), null);
    }
}
