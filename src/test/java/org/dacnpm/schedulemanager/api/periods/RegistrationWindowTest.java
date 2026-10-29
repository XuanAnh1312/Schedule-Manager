package org.dacnpm.schedulemanager.api.periods;

import org.dacnpm.schedulemanager.api.periods.RegistrationWindow.State;
import org.dacnpm.schedulemanager.common.BusinessTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Quy tắc cửa sổ đăng ký (TU-02) — biên thời gian theo giờ server, múi giờ Asia/Ho_Chi_Minh. */
class RegistrationWindowTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 9, 20, 8, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 5, 23, 59);

    @Test
    void draft_isHidden() {
        assertEquals(State.HIDDEN, RegistrationWindow.evaluate(period(PeriodStatus.DRAFT, true), vn(2026, 9, 25, 9, 0)));
    }

    @Test
    void open_beforeOpenAt_isUpcoming() {
        assertEquals(State.UPCOMING, RegistrationWindow.evaluate(period(PeriodStatus.OPEN, true), vn(2026, 9, 20, 7, 59)));
    }

    @Test
    void open_betweenOpenAtAndDeadline_isOpen() {
        PeriodRecord p = period(PeriodStatus.OPEN, true);
        assertEquals(State.OPEN, RegistrationWindow.evaluate(p, vn(2026, 9, 20, 8, 0)));
        assertEquals(State.OPEN, RegistrationWindow.evaluate(p, vn(2026, 10, 5, 23, 58)));
    }

    @Test
    void autoLock_atOrAfterDeadline_isClosed_evenIfJobHasNotRun() {
        PeriodRecord p = period(PeriodStatus.OPEN, true);
        assertEquals(State.CLOSED, RegistrationWindow.evaluate(p, vn(2026, 10, 5, 23, 59)));
        // Tình huống trong tài liệu: kiểm tra ngày 26/10 vẫn còn "Đang mở" → giờ phải đóng.
        assertEquals(State.CLOSED, RegistrationWindow.evaluate(p, vn(2026, 10, 26, 10, 0)));
    }

    @Test
    void manualClose_afterDeadline_staysOpenUntilAdminLocks() {
        PeriodRecord p = period(PeriodStatus.OPEN, false);
        assertEquals(State.OPEN, RegistrationWindow.evaluate(p, vn(2026, 10, 26, 10, 0)));
        assertEquals("Đang mở đăng ký — kỳ đóng thủ công (hạn tham khảo 05/10/2026 23:59)",
                RegistrationWindow.label(p, State.OPEN, VN));
    }

    @Test
    void locked_and_archived() {
        assertEquals(State.CLOSED, RegistrationWindow.evaluate(period(PeriodStatus.LOCKED, false), vn(2026, 9, 25, 9, 0)));
        assertEquals(State.ARCHIVED, RegistrationWindow.evaluate(period(PeriodStatus.ARCHIVED, true), vn(2026, 9, 25, 9, 0)));
    }

    @Test
    void closedLabel_showsDeadlineInBusinessTimezone() {
        PeriodRecord p = period(PeriodStatus.LOCKED, true);
        assertEquals("Đã đóng đăng ký — hạn chót 05/10/2026 23:59", RegistrationWindow.label(p, State.CLOSED, VN));
    }

    private static Instant vn(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(VN).toInstant();
    }

    private static PeriodRecord period(PeriodStatus status, boolean autoLock) {
        return new PeriodRecord("p1", "Kỳ tháng 10", "monthly", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                BusinessTime.toUtc(OPEN_AT, VN), BusinessTime.toUtc(DEADLINE, VN), autoLock, true, status, 0,
                null, null, List.of(1, 2, 3, 4, 5, 6), List.of(), List.of(), List.of());
    }
}
