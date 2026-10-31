package org.dacnpm.schedulemanager.schedule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.EmployeeLoad;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.ShiftOutcome;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.rules.EffectiveRules;
import org.dacnpm.schedulemanager.schedule.rules.EmployeeRules;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.junit.jupiter.api.Test;

/** TW-03: tiêu chí nghiệm thu của thuật toán phân lịch. day(0) là thứ hai, kỳ day(0)..day(27). */
class ScheduleEngineTest {

    private final ScheduleEngine engine = new ScheduleEngine(new AssignmentValidator());

    private static ScheduleRules rules(double perDay, double perWeek, double minRest, Double targetWeek, Integer consecutive) {
        return new ScheduleRules(perDay, perWeek, 500, minRest, targetWeek, consecutive, Map.of());
    }

    private static ScheduleContext ctx(ScheduleRules rules, List<Employee> staff, List<ShiftInfo> shifts,
                                       List<Assignment> saved, Set<String> replaced) {
        return new ScheduleContext(PERIOD, rules, staff, shifts, freeAllPeriod(staff), Map.of(), saved, replaced);
    }

    private static ShiftInfo userShift(String id, LocalDate date, LocalTime start, LocalTime end, int breakMinutes) {
        return shift(id, date, start, end, breakMinutes, 1, "user");
    }

    private static ShiftInfo named(String id, LocalDate date, Set<String> mandatory, Set<String> preferred, int total) {
        return new ShiftInfo(id, "Ca " + id, date, t(8, 0), t(12, 0), 0, "user", total, null, null, mandatory, preferred,
                null, null, null, null);
    }

    private static List<String> ids(List<ShiftInfo> shifts) {
        return shifts.stream().map(ShiftInfo::id).toList();
    }

    private static ShiftOutcome outcome(EngineResult result, String shiftId) {
        return result.shifts().stream().filter(s -> s.shiftId().equals(shiftId)).findFirst().orElseThrow();
    }

    private static EmployeeLoad load(EngineResult result, String userId) {
        return result.employees().stream().filter(e -> e.userId().equals(userId)).findFirst().orElseThrow();
    }

    // ── Nghiệm thu: 60 × 125% = 75 giờ và không vượt ───────────────────────

    @Test
    void weeklyCeilingOf60x125Percent_isReachedExactlyAndNeverExceeded() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 125.0));
        List<ShiftInfo> shifts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            shifts.add(userShift("d" + i, day(i), t(7, 0), t(20, 0), 0)); // 5 × 13h = 65h
        }
        shifts.add(userShift("sat", day(5), t(8, 0), t(18, 0), 0));      // +10h = 75h
        shifts.add(userShift("sun", day(6), t(8, 0), t(10, 0), 0));      // +2h sẽ là 77h
        EngineResult result = engine.run(ctx(rules(16, 100, 11, 60.0, null), staff, shifts, List.of(), Set.of()),
                ids(shifts));

        assertThat(load(result, "u1").plannedHours()).isEqualTo(75.0);
        ShiftOutcome sunday = outcome(result, "sun");
        assertThat(sunday.coverage()).isEqualTo(EngineResult.UNDERSTAFFED);
        assertThat(sunday.rejections()).singleElement().satisfies(r ->
                assertThat(r.violations()).anySatisfy(v -> {
                    assertThat(v.code()).isEqualTo(ViolationCode.HOURS_PER_WEEK);
                    assertThat(v.limit()).isEqualTo(75.0);
                    assertThat(v.message()).contains("60 × 125% = 75");
                }));
    }

    @Test
    void whenEveryCandidateIsAtTheCeiling_theShiftStaysUnderstaffed() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo a = userShift("a", day(0), t(8, 0), t(16, 0), 0);
        ShiftInfo b = userShift("b", day(0), t(8, 0), t(16, 0), 0);
        ShiftInfo extra = userShift("extra", day(1), t(8, 0), t(12, 0), 0);
        List<Assignment> saved = List.of(saved("x1", "a", "u1"), saved("x2", "b", "u2"));

        EngineResult result = engine.run(ctx(rules(16, 8, 11, null, null), staff, List.of(a, b, extra), saved, Set.of()),
                List.of("extra"));

        assertThat(result.assignments()).isEmpty();
        assertThat(result.summary().understaffed()).isEqualTo(1);
        assertThat(result.summary().unfilledSlots()).isEqualTo(1);
        assertThat(outcome(result, "extra").rejections())
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.violations()).anyMatch(v -> v.code() == ViolationCode.HOURS_PER_WEEK));
        assertThat(result.warnings()).anyMatch(w -> w.contains("giữ thiếu người"));
    }

    // ── Nghiệm thu: nghỉ tối thiểu, ca đêm và phân công cùng lần chạy ───────

    @Test
    void minimumRest_blocksAMorningShiftAfterANightShiftAssignedInTheSameRun() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo night = userShift("night", day(0), t(22, 0), t(6, 0), 0);
        ShiftInfo morning = userShift("morning", day(1), t(10, 0), t(14, 0), 0);

        EngineResult result = engine.run(ctx(rules(12, 75, 11, null, null), staff, List.of(night, morning), List.of(),
                Set.of()), List.of("night", "morning"));

        assertThat(result.assignments()).singleElement()
                .satisfies(a -> assertThat(a.shiftId()).isEqualTo("night"));
        assertThat(outcome(result, "morning").rejections()).singleElement()
                .satisfies(r -> assertThat(r.violations()).anyMatch(v -> v.code() == ViolationCode.MIN_REST));
    }

    @Test
    void overnightShiftHours_areSplitAcrossTheWeekBoundary() {
        // Tuần 1 đã có 72h; ca đêm chủ nhật 22:00-06:00 chỉ thêm 2h vào tuần 1 nên vẫn trong trần 75h.
        List<Employee> staff = List.of(employee("u1", "lvl1", 125.0));
        List<ShiftInfo> shifts = new ArrayList<>(baseWeekShifts());
        List<Assignment> saved = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            saved.add(saved("a" + i, "base" + i, "u1"));
        }
        ShiftInfo sundayNight = userShift("sunNight", day(6), t(22, 0), t(6, 0), 0);
        ShiftInfo sundayDay = userShift("sunDay", day(6), t(8, 0), t(16, 0), 0);
        shifts.add(sundayNight);
        shifts.add(sundayDay);
        ScheduleRules rules = rules(12, 75, 11, 60.0, null);

        EngineResult night = engine.run(ctx(rules, staff, shifts, saved, Set.of()), List.of("sunNight"));
        EngineResult day = engine.run(ctx(rules, staff, shifts, saved, Set.of()), List.of("sunDay"));

        assertThat(night.assignments()).hasSize(1);
        assertThat(day.assignments()).isEmpty();
        assertThat(outcome(day, "sunDay").rejections()).singleElement()
                .satisfies(r -> assertThat(r.violations()).anyMatch(v -> v.code() == ViolationCode.HOURS_PER_WEEK));
    }

    @Test
    void overnightShiftOnTheLastDay_countsOnlyTheHoursInsideThePeriod() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo lastNight = userShift("last", day(27), t(22, 0), t(6, 0), 0);

        EngineResult result = engine.run(ctx(rules(12, 75, 11, null, null), staff, List.of(lastNight), List.of(), Set.of()),
                List.of("last"));

        assertThat(load(result, "u1").plannedHours()).isEqualTo(2.0);
    }

    @Test
    void breakTime_isDeductedFromTheHoursCounted() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo withBreak = userShift("s0", day(0), t(7, 0), t(20, 0), 60);

        EngineResult result = engine.run(ctx(rules(12, 75, 11, null, null), staff, List.of(withBreak), List.of(), Set.of()),
                List.of("s0"));

        assertThat(load(result, "u1").plannedHours()).isEqualTo(12.0);
    }

    // ── Nghiệm thu: chạy lại không nhân đôi giờ, phân công hủy không tính ──

    @Test
    void rerunWithReplacedAssignments_doesNotDoubleCountHours() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 125.0));
        List<ShiftInfo> shifts = baseWeekShifts();
        List<Assignment> saved = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            saved.add(saved("a" + i, "base" + i, "u1"));
        }
        Set<String> replaced = Set.of("a0", "a1", "a2", "a3", "a4", "a5");
        ScheduleRules rules = rules(12, 75, 11, 60.0, 6);

        EngineResult rerun = engine.run(ctx(rules, staff, shifts, saved, replaced), ids(shifts));
        assertThat(rerun.assignments()).hasSize(6);
        assertThat(load(rerun, "u1").savedHours()).isZero();
        assertThat(load(rerun, "u1").plannedHours()).isEqualTo(72.0);

        EngineResult keep = engine.run(ctx(rules, staff, shifts, saved, Set.of()), ids(shifts));
        assertThat(keep.assignments()).isEmpty();
        assertThat(keep.summary().full()).isEqualTo(6);
        assertThat(load(keep, "u1").savedHours()).isEqualTo(72.0);
    }

    @Test
    void cancelledAssignments_areNotCountedInTotalHours() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 125.0));
        List<ShiftInfo> shifts = new ArrayList<>(baseWeekShifts());
        List<Assignment> cancelled = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cancelled.add(new Assignment("c" + i, "base" + i, "u1", Assignment.CANCELLED));
        }

        EngineResult result = engine.run(ctx(rules(12, 75, 11, 60.0, 6), staff, shifts, cancelled, Set.of()), ids(shifts));

        assertThat(result.assignments()).hasSize(6);
        assertThat(load(result, "u1").savedHours()).isZero();
    }

    // ── Thiếu thông số bắt buộc: báo đúng nhân viên/thông số ───────────────

    @Test
    void missingParameter_isReportedForTheRightEmployeeAndParameter() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0),
                new Employee("u2", "Nhân viên u2", true, "lvl1", null, null, null, null, null));
        ShiftInfo s0 = userShift("s0", day(0), t(8, 0), t(12, 0), 0);

        EngineResult result = engine.run(ctx(rules(12, 75, 11, 60.0, null), staff, List.of(s0), List.of(), Set.of()),
                List.of("s0"));

        assertThat(result.assignments()).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo("u1"));
        assertThat(result.missingParameters()).singleElement().satisfies(m -> {
            assertThat(m.userId()).isEqualTo("u2");
            assertThat(m.parameter()).isEqualTo("target_rate");
            assertThat(m.employeeName()).isEqualTo("Nhân viên u2");
        });
    }

    @Test
    void effectiveRulesFromTheRulesModule_overrideTheCompanyDefaults() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo s0 = userShift("s0", day(0), t(8, 0), t(16, 0), 0);
        ShiftInfo s1 = userShift("s1", day(1), t(8, 0), t(16, 0), 0);
        EmployeeRules tenHoursPerWeek = new EmployeeRules(12, 10, null, 11, null, null, "user 10 giờ/tuần",
                100, 0, List.of());
        EffectiveRules effective = (userId, date) -> tenHoursPerWeek;
        ScheduleContext ctx = new ScheduleContext(PERIOD, rules(12, 75, 11, null, null), staff, List.of(s0, s1),
                freeAllPeriod(staff), Map.of(), List.of(), Set.of(), effective);

        EngineResult result = engine.run(ctx, List.of("s0", "s1"));

        assertThat(result.assignments()).hasSize(1);
        assertThat(outcome(result, "s1").rejections()).singleElement().satisfies(r ->
                assertThat(r.violations()).anySatisfy(v -> assertThat(v.limit()).isEqualTo(10.0)));
    }

    @Test
    void missingParameterFromTheRulesModule_blocksThatEmployeeOnly() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo s0 = userShift("s0", day(0), t(8, 0), t(12, 0), 0);
        EmployeeRules ready = EmployeeRules.derive(rules(12, 75, 11, null, null), staff.get(0), PERIOD);
        EmployeeRules notReady = new EmployeeRules(12, 75, null, 11, null, null, null, null, null,
                List.of(new EmployeeRules.MissingParameter("max_hours_per_day", "Giờ tối đa mỗi ngày", "Chưa cấu hình.")));
        EffectiveRules effective = (userId, date) -> "u1".equals(userId) ? notReady : ready;
        ScheduleContext ctx = new ScheduleContext(PERIOD, rules(12, 75, 11, null, null), staff, List.of(s0),
                freeAllPeriod(staff), Map.of(), List.of(), Set.of(), effective);

        EngineResult result = engine.run(ctx, List.of("s0"));

        assertThat(result.assignments()).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo("u2"));
        assertThat(result.missingParameters()).singleElement().satisfies(m -> {
            assertThat(m.userId()).isEqualTo("u1");
            assertThat(m.parameter()).isEqualTo("max_hours_per_day");
        });
    }

    // ── Tiêu chí mềm và lý do chọn ─────────────────────────────────────────

    @Test
    void balancing_picksTheEmployeeFurthestBelowTarget_andExplainsWhy() {
        ScheduleRules rules = new ScheduleRules(12, 75, 500, 11, null, null, Map.of(), Map.of(), 80.0,
                ScheduleRules.MOST_NEEDED);
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo done = userShift("done", day(0), t(8, 0), t(16, 0), 0);
        ShiftInfo open = userShift("open", day(2), t(8, 0), t(12, 0), 0);

        EngineResult result = engine.run(ctx(rules, staff, List.of(done, open), List.of(saved("x", "done", "u1")),
                Set.of()), List.of("open"));

        assertThat(result.assignments()).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo("u2"));
        assertThat(outcome(result, "open").decisions()).singleElement().satisfies(d -> {
            assertThat(d.eligibleCandidates()).isEqualTo(2);
            assertThat(d.reasons()).anyMatch(r -> r.contains("Còn thiếu 80 giờ"));
        });
    }

    @Test
    void preferredEmployee_isChosenFirst_withTheReason() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo s0 = named("s0", day(0), Set.of(), Set.of("u2"), 1);

        EngineResult result = engine.run(ctx(rules(12, 75, 11, null, null), staff, List.of(s0), List.of(), Set.of()),
                List.of("s0"));

        assertThat(outcome(result, "s0").decisions()).singleElement().satisfies(d -> {
            assertThat(d.userId()).isEqualTo("u2");
            assertThat(d.slot()).isEqualTo("preferred");
        });
    }

    @Test
    void ineligibleMandatoryEmployee_isReplacedByAValidOne_withAWarning() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo s0 = named("s0", day(0), Set.of("u1"), Set.of(), 1);
        ScheduleContext ctx = new ScheduleContext(PERIOD, rules(12, 75, 11, null, null), staff, List.of(s0),
                freeAllPeriod(staff), Map.of("u1", Set.of(day(0))), List.of(), Set.of());

        EngineResult result = engine.run(ctx, List.of("s0"));

        assertThat(result.assignments()).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo("u2"));
        assertThat(outcome(result, "s0").rejections()).singleElement().satisfies(r -> {
            assertThat(r.userId()).isEqualTo("u1");
            assertThat(r.violations()).anyMatch(v -> v.code() == ViolationCode.UNAVAILABLE);
        });
        assertThat(result.warnings()).anyMatch(w -> w.contains("bắt buộc"));
    }

    @Test
    void levelSlot_prefersTheExactLevel_andOnlyAllowsHigherLevelsAsSubstitutes() {
        ScheduleRules rules = new ScheduleRules(12, 75, 500, 11, null, null, Map.of("lvl3", Set.of("lvl1")),
                Map.of("lvl1", 1, "lvl3", 3));
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u3", "lvl3", 100.0));
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "level"); // cần 1 người lvl1

        EngineResult both = engine.run(ctx(rules, staff, List.of(s0), List.of(), Set.of()), List.of("s0"));
        assertThat(both.assignments()).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo("u1"));

        ScheduleContext onlySenior = new ScheduleContext(PERIOD, rules, staff, List.of(s0), freeAllPeriod(staff),
                Map.of("u1", Set.of(day(0))), List.of(), Set.of());
        EngineResult substitute = engine.run(onlySenior, List.of("s0"));
        assertThat(outcome(substitute, "s0").decisions()).singleElement().satisfies(d -> {
            assertThat(d.userId()).isEqualTo("u3");
            assertThat(d.reasons()).anyMatch(r -> r.contains("được phép thay"));
        });
    }

    @Test
    void existingAssignmentsAreKept_andOnlyTheGapIsFilled() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0),
                employee("u3", "lvl1", 100.0));
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 2, "user");

        EngineResult result = engine.run(ctx(rules(12, 75, 11, null, null), staff, List.of(s0),
                List.of(saved("x", "s0", "u1")), Set.of()), List.of("s0"));

        ShiftOutcome o = outcome(result, "s0");
        assertThat(o.alreadyAssigned()).isEqualTo(1);
        assertThat(o.planned()).isEqualTo(1);
        assertThat(o.coverage()).isEqualTo(EngineResult.FULL);
        assertThat(result.assignments()).noneMatch(a -> a.userId().equals("u1"));
    }
}
