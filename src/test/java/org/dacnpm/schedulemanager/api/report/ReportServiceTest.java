package org.dacnpm.schedulemanager.api.report;

import org.dacnpm.schedulemanager.api.attendance.AttendanceMath;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure arithmetic test; no SQL Server needed to run this suite. */
class ReportServiceTest {
    @Test void scheduledAndTargetAreNeverConfusedWithActual() {
        var target = new BigDecimal("740");
        var scheduled = new BigDecimal("808");
        assertEquals(109, ReportService.ratio(scheduled,target));
        assertEquals(new BigDecimal("68"),scheduled.subtract(target));
        assertEquals("Vượt 68 giờ",ReportService.progressLabel(scheduled,target));
    }
    @Test void zeroTargetMustDisplayInsufficientData() {
        assertNull(ReportService.ratio(new BigDecimal("8"),BigDecimal.ZERO));
        assertEquals("Chưa đủ dữ liệu",ReportService.progressLabel(new BigDecimal("8"),BigDecimal.ZERO));
    }
    @Test void overnightAndBreakTimes() {
        LocalDateTime start=LocalDateTime.of(2026,10,30,22,0);
        LocalDateTime end=LocalDateTime.of(2026,10,31,6,0);
        assertEquals(450,AttendanceMath.workedMinutes(start,end,30));
        assertEquals(0,AttendanceMath.overtimeMinutes(start,end,30,start,end));
        assertEquals(5,AttendanceMath.lateMinutes(start.plusMinutes(10),start,5));
        assertEquals(480,AttendanceMath.nightMinutes(start,end,ZoneId.of("UTC")));
        assertThrows(IllegalArgumentException.class,()->AttendanceMath.workedMinutes(end,start,0));
    }
}
