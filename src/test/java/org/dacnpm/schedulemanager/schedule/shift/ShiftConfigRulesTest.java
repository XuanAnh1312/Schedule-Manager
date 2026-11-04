package org.dacnpm.schedulemanager.schedule.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.day;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.t;

import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShiftConfigRulesTest {

    private static ShiftRequest userShift(LocalTime start, LocalTime end, int breakMinutes, int total,
                                          Integer min, Integer max, Set<String> mandatory, Set<String> preferred) {
        return new ShiftRequest("Ca test", day(0), start, end, breakMinutes, null, "user", total, min, max, null,
                null, mandatory, preferred, null, null, null);
    }

    @Test
    void validOvernightShiftWithBreak_hasNoErrors() {
        ShiftRequest r = userShift(t(22, 0), t(6, 0), 30, 2, 1, 3, Set.of("u1"), Set.of("u2"));
        assertThat(ShiftConfigRules.check(r)).isEmpty();
    }

    @Test
    void breakMustBeShorterThanTheShift_includingOvernight() {
        assertThat(ShiftConfigRules.check(userShift(t(22, 0), t(23, 0), 60, 1, null, null, Set.of("u1"), Set.of())))
                .anyMatch(e -> e.contains("Giờ nghỉ"));
        assertThat(ShiftConfigRules.check(userShift(t(22, 0), t(6, 0), 479, 1, null, null, Set.of("u1"), Set.of())))
                .isEmpty();
        assertThat(ShiftConfigRules.check(userShift(t(22, 0), t(6, 0), 480, 1, null, null, Set.of("u1"), Set.of())))
                .anyMatch(e -> e.contains("Giờ nghỉ"));
    }

    @Test
    void startEqualToEnd_isRejected() {
        assertThat(ShiftConfigRules.check(userShift(t(8, 0), t(8, 0), 0, 1, null, null, Set.of("u1"), Set.of())))
                .anyMatch(e -> e.contains("trùng giờ kết thúc"));
    }

    @Test
    void minAndMax_mustBracketTheRequiredTotal() {
        assertThat(ShiftConfigRules.check(userShift(t(8, 0), t(12, 0), 0, 3, 4, null, Set.of("u1"), Set.of())))
                .anyMatch(e -> e.contains("tối thiểu"));
        assertThat(ShiftConfigRules.check(userShift(t(8, 0), t(12, 0), 0, 3, null, 2, Set.of("u1"), Set.of())))
                .anyMatch(e -> e.contains("tối đa"));
    }

    @Test
    void levelMode_totalOfLevelsMustEqualTotalRequired() {
        ShiftRequest r = new ShiftRequest("Ca level", day(0), t(8, 0), t(12, 0), 0, null, "level", 3, null, null, null,
                Map.of("lvl1", 1, "lvl2", 1), null, null, null, null, null);
        assertThat(ShiftConfigRules.check(r)).anyMatch(e -> e.contains("Tổng Level (2)"));
    }

    @Test
    void userMode_mandatoryAndPreferredCannotOverlap_andMandatoryCannotExceedTotal() {
        assertThat(ShiftConfigRules.check(userShift(t(8, 0), t(12, 0), 0, 2, null, null, Set.of("u1"), Set.of("u1"))))
                .anyMatch(e -> e.contains("vừa bắt buộc vừa ưu tiên"));
        assertThat(ShiftConfigRules.check(userShift(t(8, 0), t(12, 0), 0, 1, null, null, Set.of("u1", "u2"), Set.of())))
                .anyMatch(e -> e.contains("vượt tổng nhu cầu"));
    }

    @Test
    void combinedMode_mandatoryPlusLevelSlotsCannotExceedTotal() {
        ShiftRequest r = new ShiftRequest("Ca combined", day(0), t(8, 0), t(12, 0), 0, null, "combined", 2, null, null, null,
                Map.of("lvl1", 2), Set.of("u1"), null, null, null, null);
        assertThat(ShiftConfigRules.check(r)).anyMatch(e -> e.contains("vượt tổng nhu cầu"));
    }

    @Test
    void blankNameAndMissingFields_areAllReported() {
        ShiftRequest r = new ShiftRequest(" ", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertThat(ShiftConfigRules.check(r)).hasSizeGreaterThanOrEqualTo(5);
    }
}
