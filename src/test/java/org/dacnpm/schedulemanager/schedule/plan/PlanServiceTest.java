package org.dacnpm.schedulemanager.schedule.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.engine.ScheduleEngine;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanRow;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanDiff;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanOptions;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.RerunView;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TW-04: chạy thử, lưu nháp, chạy lại, so sánh; nháp không chạm lịch chính thức. */
class PlanServiceTest {

    /** Bảng schedule_plans/plan_assignments trong bộ nhớ. Không có phương thức nào ghi lịch chính thức. */
    static final class InMemoryPlans extends PlanRepository {
        final Map<String, String> periodStatus = new HashMap<>();
        final Map<String, PlanRow> rows = new LinkedHashMap<>();
        final Map<String, List<PlanPair>> pairs = new HashMap<>();

        InMemoryPlans() {
            super(null);
        }

        @Override
        public Optional<String> periodStatus(String periodId) {
            return Optional.ofNullable(periodStatus.get(periodId));
        }

        @Override
        public void insert(String id, String periodId, String name, String shiftIdsJson, String resultJson, String createdBy) {
            LocalDateTime now = LocalDateTime.now();
            rows.put(id, new PlanRow(id, periodId, name, DRAFT, 1, 1, shiftIdsJson, resultJson, createdBy, now, now));
        }

        @Override
        public boolean updateRun(String id, int expectedVersion, String shiftIdsJson, String resultJson) {
            PlanRow r = rows.get(id);
            if (r == null || r.version() != expectedVersion || !DRAFT.equals(r.status())) {
                return false;
            }
            rows.put(id, new PlanRow(id, r.periodId(), r.name(), r.status(), r.version() + 1, r.runCount() + 1,
                    shiftIdsJson, resultJson, r.createdBy(), r.createdAt(), LocalDateTime.now()));
            return true;
        }

        @Override
        public boolean updateStatus(String id, int expectedVersion, String fromStatus, String toStatus) {
            PlanRow r = rows.get(id);
            if (r == null || r.version() != expectedVersion || !fromStatus.equals(r.status())) {
                return false;
            }
            rows.put(id, new PlanRow(id, r.periodId(), r.name(), toStatus, r.version() + 1, r.runCount(),
                    r.shiftIdsJson(), r.resultJson(), r.createdBy(), r.createdAt(), LocalDateTime.now()));
            return true;
        }

        @Override
        public Optional<PlanRow> find(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<PlanRow> listByPeriod(String periodId) {
            return rows.values().stream().filter(r -> r.periodId().equals(periodId)).toList();
        }

        @Override
        public void replaceAssignments(String planId, List<PlanPair> list) {
            pairs.put(planId, List.copyOf(list));
        }

        @Override
        public List<PlanPair> assignments(String planId) {
            return pairs.getOrDefault(planId, List.of());
        }

        @Override
        public List<String> unfinishedPlans(String periodId) {
            return rows.values().stream().filter(r -> r.periodId().equals(periodId) && UNFINISHED.contains(r.status()))
                    .map(r -> r.id() + " — " + r.status()).toList();
        }
    }

    private final InMemoryPlans plans = new InMemoryPlans();
    private final ScheduleDataRepository data = mock(ScheduleDataRepository.class);
    private final ShiftRepository history = mock(ShiftRepository.class);
    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(plans, data, new ScheduleEngine(new AssignmentValidator()), history,
                new ObjectMapper().findAndRegisterModules());
    }

    private void given(String status, List<Employee> staff, List<ShiftInfo> shifts, List<Assignment> saved,
                       Map<String, Set<LocalDate>> blocked) {
        plans.periodStatus.put("p1", status);
        when(data.load("p1")).thenReturn(Optional.of(new ScheduleData(PERIOD, rules(12, 60.0), staff, shifts,
                List.of(), freeAllPeriod(staff), saved, blocked)));
    }

    private static ShiftInfo s0() {
        return shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user");
    }

    @Test
    void dryRun_returnsTheResult_andWritesNothing() {
        given("open", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), List.of(), Map.of());

        var view = service.dryRun("p1", new PlanOptions(null, false));

        assertThat(view.result().assignments()).hasSize(1);
        assertThat(view.shiftIds()).containsExactly("s0");
        assertThat(plans.rows).isEmpty();
        assertThat(plans.pairs).isEmpty();
        verifyNoInteractions(history);
    }

    @Test
    void dryRun_isRefusedWhenThePeriodIsNotOpenOrLocked() {
        given("draft", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), List.of(), Map.of());

        assertThatThrownBy(() -> service.dryRun("p1", null))
                .isInstanceOf(PlanException.class)
                .satisfies(e -> assertThat(((PlanException) e).code()).isEqualTo(PlanException.PERIOD_NOT_READY));
    }

    @Test
    void create_requiresALockedPeriod() {
        given("open", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), List.of(), Map.of());

        assertThatThrownBy(() -> service.create("p1", "Phương án A", null, "admin"))
                .isInstanceOf(PlanException.class)
                .satisfies(e -> assertThat(((PlanException) e).code()).isEqualTo(PlanException.PERIOD_NOT_READY));
        assertThat(plans.rows).isEmpty();
    }

    @Test
    void create_savesADraftWithItsAssignments_andOnlyWritesHistoryOutsideThePlanTables() {
        given("locked", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), List.of(), Map.of());

        PlanView plan = service.create("p1", " Phương án A ", null, "admin");

        assertThat(plan.status()).isEqualTo("draft");
        assertThat(plan.name()).isEqualTo("Phương án A");
        assertThat(plan.version()).isEqualTo(1);
        assertThat(plan.result().summary().full()).isEqualTo(1);
        assertThat(plans.assignments(plan.id())).containsExactly(new PlanPair("s0", "u1"));
        // Lịch chính thức không bị chạm: ShiftRepository chỉ được dùng để ghi lịch sử.
        verify(history).writeHistory(eq("plan_create"), anyString(), anyString(), eq("admin"), any(), any(), any());
        verifyNoMoreInteractions(history);
    }

    @Test
    void rerun_replacesTheDraftAssignments_andReportsWhatChanged() {
        Employee u1 = employee("u1", "lvl1", 100.0);
        given("locked", List.of(u1), List.of(s0()), List.of(), Map.of());
        PlanView first = service.create("p1", "Phương án A", null, "admin");

        // Sau đó u1 báo không thể làm ngày 0 và có thêm u2.
        given("locked", List.of(u1, employee("u2", "lvl1", 100.0)), List.of(s0()), List.of(),
                Map.of("u1", Set.of(day(0))));
        RerunView rerun = service.rerun(first.id(), 1, "admin");

        assertThat(rerun.plan().version()).isEqualTo(2);
        assertThat(rerun.plan().runCount()).isEqualTo(2);
        assertThat(plans.assignments(first.id())).containsExactly(new PlanPair("s0", "u2"));
        assertThat(rerun.changes().addedAssignments()).containsExactly(new PlanPair("s0", "u2"));
        assertThat(rerun.changes().removedAssignments()).containsExactly(new PlanPair("s0", "u1"));
        assertThat(rerun.changes().unchanged()).isZero();
    }

    @Test
    void rerun_withTheSameData_isStable() {
        given("locked", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), List.of(), Map.of());
        PlanView first = service.create("p1", "Phương án A", null, "admin");

        RerunView rerun = service.rerun(first.id(), 1, "admin");

        assertThat(rerun.changes().added()).isZero();
        assertThat(rerun.changes().removed()).isZero();
        assertThat(rerun.changes().unchanged()).isEqualTo(1);
    }

    @Test
    void rerun_withAStaleVersion_isAConflict() {
        given("locked", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), List.of(), Map.of());
        PlanView first = service.create("p1", "Phương án A", null, "admin");
        service.rerun(first.id(), 1, "admin");

        assertThatThrownBy(() -> service.rerun(first.id(), 1, "admin"))
                .isInstanceOf(PlanException.class)
                .satisfies(e -> assertThat(((PlanException) e).code()).isEqualTo(PlanException.VERSION_CONFLICT));
    }

    @Test
    void cancel_requiresAReason_andACancelledPlanCannotBeRerun() {
        given("locked", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), List.of(), Map.of());
        PlanView first = service.create("p1", "Phương án A", null, "admin");

        assertThatThrownBy(() -> service.cancel(first.id(), 1, " ", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        PlanView cancelled = service.cancel(first.id(), 1, "Làm lại với dữ liệu mới", "admin");

        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(service.unfinishedPlans("p1")).isEmpty();
        assertThatThrownBy(() -> service.rerun(first.id(), cancelled.version(), "admin"))
                .isInstanceOf(PlanException.class)
                .satisfies(e -> assertThat(((PlanException) e).code()).isEqualTo(PlanException.PLAN_NOT_EDITABLE));
    }

    @Test
    void shiftsOutsideThePlannableSet_areRejected() {
        given("open", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), List.of(), Map.of());

        assertThatThrownBy(() -> service.dryRun("p1", new PlanOptions(List.of("s0", "ghost"), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void replaceExisting_regeneratesWithoutCountingTheReplacedAssignments() {
        List<Assignment> saved = List.of(saved("a0", "s0", "u1"));
        given("open", List.of(employee("u1", "lvl1", 100.0)), List.of(s0()), saved, Map.of());

        var keep = service.dryRun("p1", new PlanOptions(null, false)).result();
        var replace = service.dryRun("p1", new PlanOptions(null, true)).result();

        assertThat(keep.assignments()).isEmpty();
        assertThat(keep.employees().get(0).savedHours()).isEqualTo(4.0);
        assertThat(replace.assignments()).hasSize(1);
        assertThat(replace.employees().get(0).savedHours()).isZero();
        assertThat(replace.employees().get(0).plannedHours()).isEqualTo(4.0);
    }

    @Test
    void compare_listsAddedAndRemovedAssignmentsBetweenTwoPlans() {
        Employee u1 = employee("u1", "lvl1", 100.0);
        Employee u2 = employee("u2", "lvl1", 100.0);
        given("locked", List.of(u1), List.of(s0()), List.of(), Map.of());
        PlanView a = service.create("p1", "A", null, "admin");
        given("locked", List.of(u1, u2), List.of(s0()), List.of(), Map.of("u1", Set.of(day(0))));
        PlanView b = service.create("p1", "B", null, "admin");

        PlanDiff diff = service.compare(a.id(), b.id());

        assertThat(diff.addedAssignments()).containsExactly(new PlanPair("s0", "u2"));
        assertThat(diff.removedAssignments()).containsExactly(new PlanPair("s0", "u1"));
        assertThat(service.list("p1")).hasSize(2);
        assertThat(service.unfinishedPlans("p1")).hasSize(2);
    }

    @Test
    void storedResult_survivesTheJsonRoundTrip() {
        List<ShiftInfo> shifts = new ArrayList<>(List.of(s0(), shift("s1", day(1), t(22, 0), t(6, 0), 30, 2, "level")));
        given("locked", List.of(employee("u1", "lvl1", 100.0)), shifts, List.of(), Map.of());

        PlanView plan = service.create("p1", "A", new PlanOptions(null, true), "admin");
        PlanView reloaded = service.get(plan.id());

        assertThat(reloaded.options().replaceExisting()).isTrue();
        assertThat(reloaded.result()).isEqualTo(plan.result());
        assertThat(reloaded.shiftIds()).containsExactlyInAnyOrder("s0", "s1");
    }
}
