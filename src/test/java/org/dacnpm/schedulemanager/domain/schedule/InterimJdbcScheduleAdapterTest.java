package org.dacnpm.schedulemanager.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tính toán thời gian dùng cho kiểm tra trùng ca / nghỉ tối thiểu, kể cả ca qua đêm. */
class InterimJdbcScheduleAdapterTest {

    static ShiftInfo shift(int day, String start, String end) {
        return new ShiftInfo("s" + day + start, "Ca", LocalDate.of(2026, 11, day), LocalTime.parse(start),
                LocalTime.parse(end), "published", 1, 0);
    }

    @Test
    void overnightShift_endsNextDay() {
        ShiftInfo night = shift(7, "22:00", "06:00");
        assertEquals(8.0, night.hours());
        assertEquals(LocalDate.of(2026, 11, 8), night.endsAt().toLocalDate());
    }

    @Test
    void overlap_detectsOvernightIntoNextMorning() {
        assertTrue(InterimJdbcScheduleAdapter.overlaps(shift(7, "22:00", "06:00"), shift(8, "05:00", "13:00")));
        assertFalse(InterimJdbcScheduleAdapter.overlaps(shift(7, "07:00", "15:00"), shift(7, "15:00", "23:00")));
    }

    @Test
    void gapHours_isSymmetric() {
        ShiftInfo morning = shift(7, "07:00", "15:00");
        ShiftInfo evening = shift(7, "14:30", "22:30");
        ShiftInfo nextMorning = shift(8, "07:00", "15:00");
        assertEquals(8.5, InterimJdbcScheduleAdapter.gapHours(evening, nextMorning));
        assertEquals(8.5, InterimJdbcScheduleAdapter.gapHours(nextMorning, evening));
        assertTrue(InterimJdbcScheduleAdapter.overlaps(morning, evening));
    }
}
