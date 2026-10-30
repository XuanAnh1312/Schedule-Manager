package org.dacnpm.schedulemanager.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftNotFoundException;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRequest;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCandidate;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCheckService;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCheckService.LeaveCheck;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCheckService.PublishCheck;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCheckService.SwapCheck;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssignmentCheckServiceTest {

    private final ShiftRepository shifts = mock(ShiftRepository.class);
    private final ScheduleDataRepository data = mock(ScheduleDataRepository.class);
    private AssignmentCheckService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentCheckService(shifts, data, new AssignmentValidator());
        when(shifts.periodIdFor(any())).thenReturn(Optional.of("p1"));
        when(shifts.periodIdsOverlapping(any(), any())).thenReturn(List.of("p1"));
    }

    private static ShiftDetail detail(ShiftInfo s, Instant cancelledAt) {
        ShiftRequest request = new ShiftRequest(s.name(), s.date(), s.startTime(), s.endTime(), s.breakMinutes(), null,
                s.mode(), s.totalRequired(), null, null, null, null, null, null, null, null, null);
        return new ShiftDetail(s.id(), request, "not_scheduled", 1, false, null, cancelledAt,
                cancelledAt == null ? null : "admin", cancelledAt == null ? null : "đã hủy");
    }

    private void given(List<Employee> staff, List<ShiftInfo> allShifts, List<Assignment> saved,
                       Map<String, Set<LocalDate>> blocked) {
        given(staff, allShifts, saved, blocked, rules(8, 60.0));
    }

    private void given(List<Employee> staff, List<ShiftInfo> allShifts, List<Assignment> saved,
                       Map<String, Set<LocalDate>> blocked, ScheduleRules rules) {
        for (ShiftInfo s : allShifts) {
            when(shifts.findById(s.id())).thenReturn(Optional.of(detail(s, null)));
        }
        when(data.load("p1")).thenReturn(Optional.of(new ScheduleData(PERIOD, rules, staff, allShifts,
                List.of(), freeAllPeriod(staff), saved, blocked)));
    }

    @Test
    void checkAssignment_blockedDate_isReportedAsUnavailable() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        given(staff, List.of(s0), List.of(), Map.of("u1", Set.of(day(0))));

        ValidationResult result = service.checkAssignment(new AssignmentCandidate("u1", "s0", null));

        assertThat(result.has(ViolationCode.UNAVAILABLE)).isTrue();
        assertThat(result.overridable()).isTrue();
    }

    @Test
    void checkAssignment_usesTheSameWeeklyCeilingAsTheEngine() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 125.0));
        List<ShiftInfo> all = new java.util.ArrayList<>(baseWeekShifts());
        all.add(shift("sun", day(6), t(8, 0), t(12, 0), 0, 1, "user"));
        List<Assignment> saved = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            saved.add(saved("a" + i, "base" + i, "u1"));
        }
        given(staff, all, saved, Map.of());

        ValidationResult result = service.checkAssignment(new AssignmentCandidate("u1", "sun", null));

        assertThat(result.has(ViolationCode.HOURS_PER_WEEK)).isTrue();
    }

    @Test
    void checkAssignment_cancelledShift_isRejected() {
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        when(shifts.findById("s0")).thenReturn(Optional.of(detail(s0, Instant.now())));

        assertThatThrownBy(() -> service.checkAssignment(new AssignmentCandidate("u1", "s0", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkAssignment_unknownShift_isNotFound() {
        when(shifts.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkAssignment(new AssignmentCandidate("u1", "ghost", null)))
                .isInstanceOf(ShiftNotFoundException.class);
    }

    @Test
    void checkSwap_allowedWhenBothEmployeesFitTheOtherShift() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo sA = shift("sA", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        ShiftInfo sB = shift("sB", day(1), t(8, 0), t(12, 0), 0, 1, "user");
        given(staff, List.of(sA, sB), List.of(saved("a1", "sA", "u1"), saved("a2", "sB", "u2")), Map.of());

        SwapCheck check = service.checkSwap("sA", "u1", "sB", "u2");

        assertThat(check.allowed()).isTrue();
    }

    @Test
    void checkSwap_blockedWhenTheIncomingShiftClashesWithAnotherOfTheEmployee() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo sA = shift("sA", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        ShiftInfo sB = shift("sB", day(1), t(8, 0), t(12, 0), 0, 1, "user");
        ShiftInfo sC = shift("sC", day(1), t(10, 0), t(14, 0), 0, 1, "user");
        given(staff, List.of(sA, sB, sC),
                List.of(saved("a1", "sA", "u1"), saved("a2", "sB", "u2"), saved("a3", "sC", "u1")), Map.of());

        SwapCheck check = service.checkSwap("sA", "u1", "sB", "u2");

        assertThat(check.allowed()).isFalse();
        assertThat(check.userAToShiftB().has(ViolationCode.OVERLAP)).isTrue();
        assertThat(check.userBToShiftA().valid()).isTrue();
    }

    @Test
    void checkSwap_rejectsEmployeeNotAssignedToTheirShift() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo sA = shift("sA", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        ShiftInfo sB = shift("sB", day(1), t(8, 0), t(12, 0), 0, 1, "user");
        given(staff, List.of(sA, sB), List.of(saved("a2", "sB", "u2")), Map.of());

        assertThatThrownBy(() -> service.checkSwap("sA", "u1", "sB", "u2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkPublish_reportsHardViolationsOfExistingAssignments_andUnderstaffedShifts() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo s1 = shift("s1", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        ShiftInfo s2 = shift("s2", day(0), t(10, 0), t(14, 0), 0, 1, "user");
        ShiftInfo s3 = shift("s3", day(2), t(8, 0), t(12, 0), 0, 2, "user");
        given(staff, List.of(s1, s2, s3),
                List.of(saved("a1", "s1", "u1"), saved("a2", "s2", "u1"), saved("a3", "s3", "u2")), Map.of());

        PublishCheck check = service.checkPublish(List.of("s1", "s2", "s3"));

        assertThat(check.publishable()).isFalse();
        assertThat(check.needsException()).isFalse();
        assertThat(check.anyUnderstaffed()).isTrue();
        assertThat(check.shifts().get(0).hardViolations()).anyMatch(v -> v.code() == ViolationCode.OVERLAP);
        assertThat(check.shifts().get(2).understaffed()).isTrue();
        assertThat(check.shifts().get(2).hardViolations()).isEmpty();
    }

    @Test
    void checkPublish_cleanShifts_arePublishable() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo s3 = shift("s3", day(2), t(8, 0), t(12, 0), 0, 2, "user");
        given(staff, List.of(s3), List.of(saved("a1", "s3", "u1"), saved("a2", "s3", "u2")), Map.of());

        PublishCheck check = service.checkPublish(List.of("s3"));

        assertThat(check.publishable()).isTrue();
        assertThat(check.anyUnderstaffed()).isFalse();
    }

    @Test
    void checkPublish_availabilityOnlyViolation_needsAnException() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        given(staff, List.of(s0), List.of(saved("a1", "s0", "u1")), Map.of("u1", Set.of(day(0))));

        PublishCheck check = service.checkPublish(List.of("s0"));

        assertThat(check.publishable()).isFalse();
        assertThat(check.needsException()).isTrue();
        assertThat(check.shifts().get(0).hardViolations()).isEmpty();
        assertThat(check.shifts().get(0).exceptionViolations()).anyMatch(v -> v.code() == ViolationCode.UNAVAILABLE);
    }

    @Test
    void checkLeave_listsAffectedAssignments_andFlagsShiftsThatBecomeUnderstaffed() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        ShiftInfo s5 = shift("s5", day(5), t(8, 0), t(12, 0), 0, 2, "user");
        given(staff, List.of(s0, s5),
                List.of(saved("a1", "s0", "u1"), saved("a2", "s5", "u1"), saved("a3", "s5", "u2")), Map.of());

        LeaveCheck early = service.checkLeave("u1", day(0), day(2));
        assertThat(early.impacts()).singleElement().satisfies(i -> {
            assertThat(i.shiftId()).isEqualTo("s0");
            assertThat(i.filledAfterLeave()).isZero();
            assertThat(i.understaffed()).isTrue();
        });
        assertThat(early.causesUnderstaffing()).isTrue();

        LeaveCheck late = service.checkLeave("u1", day(5), day(5));
        assertThat(late.impacts()).singleElement().satisfies(i -> {
            assertThat(i.shiftId()).isEqualTo("s5");
            assertThat(i.filledAfterLeave()).isEqualTo(1);
            assertThat(i.understaffed()).isTrue();
        });

        assertThat(service.checkLeave("u1", day(3), day(4)).impacts()).isEmpty();
    }

    private static ShiftInfo withMin(ShiftInfo s, int minRequired) {
        return new ShiftInfo(s.id(), s.name(), s.date(), s.startTime(), s.endTime(), s.breakMinutes(), s.mode(),
                s.totalRequired(), s.maxAllowed(), s.levelRequirements(), s.mandatoryUserIds(), s.preferredUserIds(),
                s.requiredSkills(), s.requiredCertificates(), s.locationId(), s.requiredPositionIds(), minRequired);
    }

    @Test
    void checkPublish_belowTotalButAtMinimum_isNotUnderstaffed() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo atMin = withMin(shift("s1", day(0), t(8, 0), t(12, 0), 0, 3, "user"), 2);
        ShiftInfo belowMin = withMin(shift("s2", day(2), t(8, 0), t(12, 0), 0, 3, "user"), 2);
        given(staff, List.of(atMin, belowMin),
                List.of(saved("a1", "s1", "u1"), saved("a2", "s1", "u2"), saved("a3", "s2", "u1")), Map.of());

        PublishCheck check = service.checkPublish(List.of("s1", "s2"));

        assertThat(check.shifts().get(0)).satisfies(s -> {
            assertThat(s.required()).isEqualTo(3);
            assertThat(s.minimum()).isEqualTo(2);
            assertThat(s.understaffed()).isFalse();
        });
        assertThat(check.shifts().get(1).understaffed()).isTrue();
    }

    @Test
    void checkLeave_flagsUnderstaffingOnlyWhenFallingBelowTheMinimum() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0),
                employee("u3", "lvl1", 100.0));
        ShiftInfo s0 = withMin(shift("s0", day(0), t(8, 0), t(12, 0), 0, 3, "user"), 2);
        ShiftInfo s1 = withMin(shift("s1", day(1), t(8, 0), t(12, 0), 0, 3, "user"), 2);
        given(staff, List.of(s0, s1), List.of(
                saved("a1", "s0", "u1"), saved("a2", "s0", "u2"), saved("a3", "s0", "u3"),
                saved("a4", "s1", "u1"), saved("a5", "s1", "u2")), Map.of());

        LeaveCheck check = service.checkLeave("u1", day(0), day(1));

        assertThat(check.impacts()).hasSize(2);
        assertThat(check.impacts().get(0).understaffed()).isFalse();
        assertThat(check.impacts().get(1).understaffed()).isTrue();
        assertThat(check.impacts().get(1).minimum()).isEqualTo(2);
    }

    @Test
    void checkLeave_overnightShiftSpillingIntoTheLeaveDay_isAffected() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo night = shift("night", day(0), t(22, 0), t(6, 0), 0, 1, "user");
        given(staff, List.of(night), List.of(saved("a1", "night", "u1")), Map.of());

        assertThat(service.checkLeave("u1", day(1), day(1)).impacts()).hasSize(1);
    }

    @Test
    void checkLeave_rejectsInvalidOrTooLongRange() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        given(staff, List.of(), List.of(), Map.of());

        assertThatThrownBy(() -> service.checkLeave("u1", day(3), day(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.checkLeave("u1", day(0), day(400))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkLeave_spanningTwoPeriods_collectsEachAssignmentOnce() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo lastDay = shift("last", day(27), t(8, 0), t(12, 0), 0, 1, "user");
        ShiftInfo nextFirst = shift("next", day(28), t(8, 0), t(12, 0), 0, 1, "user");
        Period next = new Period("p2", "Kỳ sau", day(28), day(55));
        List<Assignment> saved = List.of(saved("a1", "last", "u1"), saved("a2", "next", "u1"));
        // Mỗi kỳ được nạp kèm ngày đệm của kỳ kia, nên cả hai phân công xuất hiện trong cả hai lần nạp.
        when(data.load("p1")).thenReturn(Optional.of(new ScheduleData(PERIOD, rules(8, 60.0), staff,
                List.of(lastDay, nextFirst), List.of(), freeAllPeriod(staff), saved, Map.of())));
        when(data.load("p2")).thenReturn(Optional.of(new ScheduleData(next, rules(8, 60.0), staff,
                List.of(lastDay, nextFirst), List.of(), freeAllPeriod(staff), saved, Map.of())));
        when(shifts.periodIdsOverlapping(day(27), day(28))).thenReturn(List.of("p1", "p2"));

        LeaveCheck check = service.checkLeave("u1", day(27), day(28));

        assertThat(check.impacts()).extracting(AssignmentCheckService.LeaveImpact::assignmentId)
                .containsExactly("a1", "a2");
    }

    @Test
    void checkLeave_outsideEveryPeriod_hasNoImpact() {
        when(shifts.periodIdsOverlapping(any(), any())).thenReturn(List.of());

        assertThat(service.checkLeave("u1", day(100), day(101)).impacts()).isEmpty();
    }

    @Test
    void checkBatch_countsEarlierAcceptedProposals_forOverlapAndWeeklyCeiling() {
        // target 60h × 125% = 75h/tuần; sáu ca 12h = 72h, ca thứ bảy 4h sẽ vượt trần.
        List<Employee> staff = List.of(employee("u1", "lvl1", 125.0));
        List<ShiftInfo> all = new java.util.ArrayList<>(baseWeekShifts());
        all.add(shift("sun", day(6), t(8, 0), t(12, 0), 0, 1, "user"));
        all.add(shift("clash", day(0), t(10, 0), t(14, 0), 0, 1, "user"));
        given(staff, all, List.of(), Map.of(), rules(12, 60.0));

        List<AssignmentCandidate> proposals = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            proposals.add(new AssignmentCandidate("u1", "base" + i, null));
        }
        proposals.add(new AssignmentCandidate("u1", "clash", null));
        proposals.add(new AssignmentCandidate("u1", "sun", null));

        AssignmentCheckService.BatchCheck check = service.checkBatch(proposals, Set.of());

        assertThat(check.items().subList(0, 6)).allMatch(AssignmentCheckService.BatchItem::accepted);
        assertThat(check.items().get(6).result().has(ViolationCode.OVERLAP)).isTrue();
        assertThat(check.items().get(7).result().has(ViolationCode.HOURS_PER_WEEK)).isTrue();
        assertThat(check.items().get(7).result().violations())
                .filteredOn(v -> v.code() == ViolationCode.HOURS_PER_WEEK)
                .singleElement().satisfies(v -> assertThat(v.limit()).isEqualTo(75.0));
        assertThat(check.acceptedCount()).isEqualTo(6);
        assertThat(check.allAccepted()).isFalse();
    }

    @Test
    void checkBatch_rejectedProposalsDoNotCountAgainstLaterOnes() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0));
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        given(staff, List.of(s0), List.of(), Map.of("u1", Set.of(day(0))));

        AssignmentCheckService.BatchCheck check = service.checkBatch(List.of(
                new AssignmentCandidate("u1", "s0", null), new AssignmentCandidate("u2", "s0", null)), Set.of());

        assertThat(check.items().get(0).accepted()).isFalse();
        assertThat(check.items().get(1).accepted()).isTrue();
    }

    @Test
    void checkBatch_replacedAssignmentsAreNotCountedTwice() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 125.0));
        List<ShiftInfo> all = baseWeekShifts();
        List<Assignment> saved = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            saved.add(saved("a" + i, "base" + i, "u1"));
        }
        given(staff, all, saved, Map.of(), rules(12, 60.0));
        List<AssignmentCandidate> rerun = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rerun.add(new AssignmentCandidate("u1", "base" + i, null));
        }

        Set<String> replaced = Set.of("a0", "a1", "a2", "a3", "a4", "a5");
        assertThat(service.checkBatch(rerun, replaced).allAccepted()).isTrue();
        assertThat(service.checkBatch(rerun, Set.of()).acceptedCount()).isZero();
    }

    @Test
    void checkBatch_rejectsEmptyOrIncompleteInput_andShiftsFromAnotherPeriod() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo s0 = shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        ShiftInfo outside = shift("out", day(40), t(8, 0), t(12, 0), 0, 1, "user");
        given(staff, List.of(s0, outside), List.of(), Map.of());

        assertThatThrownBy(() -> service.checkBatch(List.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.checkBatch(List.of(new AssignmentCandidate(null, "s0", null)), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.checkBatch(List.of(new AssignmentCandidate("u1", "s0", null),
                new AssignmentCandidate("u1", "out", null)), Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
