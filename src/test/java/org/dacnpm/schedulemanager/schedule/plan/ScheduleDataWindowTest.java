package org.dacnpm.schedulemanager.schedule.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.junit.jupiter.api.Test;

/** Khoảng nạp ca/phân công quanh kỳ phải đủ rộng cho tuần giáp ranh và chuỗi ngày làm liên tiếp. */
class ScheduleDataWindowTest {

    // Thứ tư 2026-01-07 đến thứ sáu 2026-01-30.
    private static final Period PERIOD = new Period("p1", "Kỳ", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 30));

    private static ScheduleRules rules(Integer maxConsecutiveDays) {
        return new ScheduleRules(12, 75, 200, 11, 60.0, maxConsecutiveDays, Map.of());
    }

    @Test
    void withoutAConsecutiveLimit_coversTheBoundaryWeeksPlusOneDay() {
        assertThat(ScheduleDataRepository.windowStart(PERIOD, rules(null))).isBeforeOrEqualTo(PERIOD.startDate().minusDays(31));
        assertThat(ScheduleDataRepository.windowEnd(PERIOD, rules(null))).isAfterOrEqualTo(PERIOD.endDate().plusDays(31));
    }

    @Test
    void shortConsecutiveLimit_keepsTheWeekBasedWindow() {
        assertThat(ScheduleDataRepository.windowStart(PERIOD, rules(2))).isBeforeOrEqualTo(PERIOD.startDate().minusDays(31));
        assertThat(ScheduleDataRepository.windowEnd(PERIOD, rules(2))).isAfterOrEqualTo(PERIOD.endDate().plusDays(31));
    }

    @Test
    void longConsecutiveLimit_widensTheWindowToSeeTheWholeStreak() {
        assertThat(ScheduleDataRepository.windowStart(PERIOD, rules(10))).isBeforeOrEqualTo(PERIOD.startDate().minusDays(31));
        assertThat(ScheduleDataRepository.windowEnd(PERIOD, rules(10))).isAfterOrEqualTo(PERIOD.endDate().plusDays(31));
    }
}
