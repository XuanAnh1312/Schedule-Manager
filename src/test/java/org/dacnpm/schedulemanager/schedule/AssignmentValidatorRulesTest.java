package org.dacnpm.schedulemanager.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.model.TimeRange;
import org.dacnpm.schedulemanager.schedule.model.UserAvailability;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCandidate;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.junit.jupiter.api.Test;

/** TW-02: Level thay thế đúng chiều và thời gian rảnh phủ ca qua nhiều khung/ngày. */
class AssignmentValidatorRulesTest {

    private static final Map<String, Integer> LEVEL_ORDER = Map.of("lvl1", 1, "lvl2", 2, "lvl3", 3);

    private final AssignmentValidator validator = new AssignmentValidator();

    // ── Level thay thế đúng chiều ────────────────────────────────────────────

    private ValidationResult checkLevel(ScheduleRules rules, String employeeLevel, String requiredLevel) {
        List<Employee> staff = List.of(employee("u1", employeeLevel, 100.0));
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "level");
        ScheduleContext ctx = context(rules, staff, List.of(s0), List.of());
        return validator.validate(ctx, new AssignmentCandidate("u1", "s0", requiredLevel));
    }

    private static ScheduleRules rulesWith(Map<String, Set<String>> substitutions) {
        return new ScheduleRules(12, 75, 200, 11, 60.0, 6, substitutions, LEVEL_ORDER);
    }

    @Test
    void higherLevelWithASubstitutionRule_fillsALowerSlot() {
        assertThat(checkLevel(rulesWith(Map.of("lvl3", Set.of("lvl1"))), "lvl3", "lvl1").valid()).isTrue();
    }

    @Test
    void ruleWrittenTheWrongWayRound_doesNotLetALowerLevelFillAHigherSlot() {
        ValidationResult result = checkLevel(rulesWith(Map.of("lvl1", Set.of("lvl3"))), "lvl1", "lvl3");

        assertThat(result.has(ViolationCode.LEVEL)).isTrue();
    }

    @Test
    void higherLevelWithoutAnyRule_isStillRejected() {
        assertThat(checkLevel(rulesWith(Map.of()), "lvl3", "lvl1").has(ViolationCode.LEVEL)).isTrue();
    }

    @Test
    void unknownLevelOrder_fallsBackToTheConfiguredRule() {
        ScheduleRules rules = new ScheduleRules(12, 75, 200, 11, 60.0, 6, Map.of("lvl1", Set.of("lvl3")));

        assertThat(checkLevel(rules, "lvl1", "lvl3").valid()).isTrue();
    }

    // ── Thời gian rảnh phủ ca ───────────────────────────────────────────────

    private ValidationResult checkCoverage(ShiftInfo shift, Map<LocalDate, List<TimeRange>> slots) {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ScheduleContext ctx = new ScheduleContext(PERIOD, rules(24, 60.0), staff, List.of(shift),
                Map.of("u1", new UserAvailability("u1", true, slots)), Map.of(), List.of(), Set.of());
        return validator.validate(ctx, new AssignmentCandidate("u1", shift.id(), null));
    }

    private static TimeRange range(int startHour, int startMinute, int endHour, int endMinute) {
        return TimeRange.of(LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }

    @Test
    void twoAdjacentSlotsOnTheSameDay_coverAShiftAcrossTheJoin() {
        ShiftInfo shift = shift("s0", day(0), t(8, 0), t(14, 0), 0, 1, "user");
        Map<LocalDate, List<TimeRange>> slots = Map.of(day(0), List.of(range(7, 0, 12, 0), range(12, 0, 15, 0)));

        assertThat(checkCoverage(shift, slots).valid()).isTrue();
    }

    @Test
    void aGapBetweenSlots_leavesTheShiftUncovered() {
        ShiftInfo shift = shift("s0", day(0), t(8, 0), t(14, 0), 0, 1, "user");
        Map<LocalDate, List<TimeRange>> slots = Map.of(day(0), List.of(range(7, 0, 11, 0), range(12, 0, 15, 0)));

        assertThat(checkCoverage(shift, slots).has(ViolationCode.NOT_AVAILABLE_TIME)).isTrue();
    }

    @Test
    void overnightShift_isCoveredByTheEveningSlotAndTheNextMorningSlot() {
        ShiftInfo night = shift("n0", day(0), t(22, 0), t(6, 0), 0, 1, "user");
        Map<LocalDate, List<TimeRange>> slots = Map.of(
                day(0), List.of(range(18, 0, 23, 59)),
                day(1), List.of(range(0, 0, 8, 0)));

        assertThat(checkCoverage(night, slots).valid()).isTrue();
    }

    @Test
    void overnightShift_withoutTheNextMorning_isNotCovered() {
        ShiftInfo night = shift("n0", day(0), t(22, 0), t(6, 0), 0, 1, "user");
        Map<LocalDate, List<TimeRange>> slots = Map.of(day(0), List.of(range(18, 0, 23, 59)));

        assertThat(checkCoverage(night, slots).has(ViolationCode.NOT_AVAILABLE_TIME)).isTrue();
    }

    @Test
    void wholeDaySlotEndingAt2359_coversAShiftEndingAtMidnight() {
        ShiftInfo late = shift("l0", day(0), t(16, 0), t(0, 0), 0, 1, "user");
        Map<LocalDate, List<TimeRange>> slots = Map.of(day(0), List.of(range(0, 0, 23, 59)));

        assertThat(checkCoverage(late, slots).valid()).isTrue();
    }

    @Test
    void earlyMorningShift_isCoveredByAnOvernightSlotRegisteredTheDayBefore() {
        ShiftInfo early = shift("e0", day(1), t(2, 0), t(6, 0), 0, 1, "user");
        Map<LocalDate, List<TimeRange>> slots = Map.of(day(0), List.of(range(20, 0, 7, 0)));

        assertThat(checkCoverage(early, slots).valid()).isTrue();
    }
}
