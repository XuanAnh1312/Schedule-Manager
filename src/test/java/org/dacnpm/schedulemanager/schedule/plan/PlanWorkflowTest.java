package org.dacnpm.schedulemanager.schedule.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.dacnpm.schedulemanager.schedule.engine.ScheduleEngine;
import org.dacnpm.schedulemanager.schedule.events.ScheduleEvent;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService.Preview;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanOptions;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.PlanWorkflowService.PublishReport;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.support.FakeExceptions;
import org.dacnpm.schedulemanager.schedule.support.FakeOfficialSchedule;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TW-05/TW-06: nháp → chờ duyệt → duyệt → công bố, chỉnh tay/kéo thả, ngoại lệ, khóa lạc quan, sự kiện. */
class PlanWorkflowTest {

    private final FakePlanRepository plans = new FakePlanRepository();
    private final FakeOfficialSchedule official = new FakeOfficialSchedule();
    private final FakeExceptions exceptionRepo = new FakeExceptions();
    private final ScheduleDataRepository data = mock(ScheduleDataRepository.class);
    private final ShiftRepository history = mock(ShiftRepository.class);
    private final List<ScheduleEvent> events = new ArrayList<>();

    private PlanService planService;
    private PlanWorkflowService workflow;
    private PlanEditService edits;
    private ExceptionService exceptions;

    private List<Employee> staff = new ArrayList<>();
    private List<ShiftInfo> shifts = new ArrayList<>();
    private Map<String, Set<LocalDate>> blocked = new HashMap<>();
    private ScheduleRules rules = rules(12, 60.0);

    @BeforeEach
    void setUp() {
        AssignmentValidator validator = new AssignmentValidator();
        RosterChecker checker = new RosterChecker(validator);
        planService = new PlanService(plans, data, new ScheduleEngine(validator), history,
                new ObjectMapper().findAndRegisterModules());
        exceptions = new ExceptionService(exceptionRepo, plans, planService, data, checker, history,
                new ObjectMapper().findAndRegisterModules());
        workflow = new PlanWorkflowService(plans, planService, data, checker, official, exceptionRepo, history, events::add);
        edits = new PlanEditService(plans, planService, data, checker, history);
        plans.periodStatus.put("p1", "locked");
        // Dữ liệu kỳ luôn phản ánh lịch chính thức hiện tại (như ScheduleDataRepository đọc từ DB).
        when(data.load("p1")).thenAnswer(inv -> Optional.of(new ScheduleData(PERIOD, rules, staff, shifts,
                List.copyOf(official.publishedShifts), freeAllPeriod(staff), official.asAssignments(), blocked)));
        staff.add(employee("u1", "lvl1", 100.0));
        staff.add(employee("u2", "lvl1", 100.0));
        shifts.add(shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user"));
        shifts.add(shift("s1", day(1), t(8, 0), t(12, 0), 0, 1, "user"));
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(PlanException.class)
                .satisfies(e -> assertThat(((PlanException) e).code()).isEqualTo(code));
    }

    private PlanView draft() {
        return planService.create("p1", "Phương án", null, "admin");
    }

    private PlanView approved(PlanView plan) {
        workflow.submit(plan.id(), plan.version(), "admin");
        workflow.approve(plan.id(), plan.version() + 1, "ok", "manager");
        return planService.get(plan.id());
    }

    // ── Luồng đầy đủ ───────────────────────────────────────────────────────

    @Test
    void draftToPublished_writesTheOfficialSchedule_andEmitsOneEvent() {
        PlanView plan = approved(draft());
        assertThat(plan.status()).isEqualTo("approved");
        assertThat(official.confirmed()).isEmpty();

        PublishReport report = workflow.publish(plan.id(), plan.version(), false, "admin");

        assertThat(report.added()).isEqualTo(2);
        assertThat(official.confirmed()).extracting(a -> a.shiftId() + ":" + a.userId())
                .containsExactlyInAnyOrderElementsOf(plan.roster().stream().map(p -> p.shiftId() + ":" + p.userId()).toList());
        assertThat(official.publishedShifts).containsExactlyInAnyOrder("s0", "s1");
        assertThat(plans.status(plan.id())).isEqualTo("published");
        assertThat(plans.periodLocks).isEqualTo(1);
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(ScheduleEvent.SCHEDULE_PUBLISHED);
            assertThat(e.eventId()).isEqualTo("schedule.published:" + plan.id() + ":v" + report.version());
            assertThat(e.recipients()).containsExactlyInAnyOrderElementsOf(
                    plan.roster().stream().map(PlanPair::userId).distinct().toList());
        });
        verify(history).writeHistory(eq("plan_publish"), anyString(), anyString(), eq("admin"), any(), any(), any());
    }

    @Test
    void draftIsNeverVisibleAsOfficialUntilPublished() {
        PlanView plan = draft();
        workflow.submit(plan.id(), plan.version(), "admin");

        assertThat(official.confirmed()).isEmpty();
        assertThat(official.publishedShifts).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void transitionsRequireTheRightStatusAndVersion() {
        PlanView plan = draft();

        assertCode(() -> workflow.publish(plan.id(), plan.version(), false, "admin"), PlanException.PLAN_NOT_EDITABLE);
        assertCode(() -> workflow.approve(plan.id(), plan.version(), null, "admin"), PlanException.PLAN_NOT_EDITABLE);
        assertCode(() -> workflow.submit(plan.id(), plan.version() + 5, "admin"), PlanException.VERSION_CONFLICT);

        workflow.submit(plan.id(), plan.version(), "admin");
        // Người thứ hai dùng version cũ: bị chặn, không ghi đè.
        assertCode(() -> workflow.submit(plan.id(), plan.version(), "other"), PlanException.PLAN_NOT_EDITABLE);
        assertThatThrownBy(() -> workflow.reject(plan.id(), plan.version() + 1, " ", "manager"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectReturnsThePlanToDraftForEditing() {
        PlanView plan = draft();
        workflow.submit(plan.id(), plan.version(), "admin");

        workflow.reject(plan.id(), plan.version() + 1, "Thiếu người ca tối", "manager");

        assertThat(plans.status(plan.id())).isEqualTo("draft");
    }

    @Test
    void publishWithAStaleVersion_changesNothing() {
        PlanView plan = approved(draft());

        assertCode(() -> workflow.publish(plan.id(), plan.version() - 1, false, "admin"), PlanException.VERSION_CONFLICT);
        assertThat(official.confirmed()).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void publishRequiresALockedPeriod() {
        PlanView plan = approved(draft());
        plans.periodStatus.put("p1", "open");

        assertCode(() -> workflow.publish(plan.id(), plan.version(), false, "admin"), PlanException.PERIOD_NOT_READY);
    }

    // ── Ràng buộc bắt buộc, ngoại lệ, thiếu người ──────────────────────────

    @Test
    void hardViolationsBlockSubmitApproveAndPublish() {
        PlanView plan = draft();
        rules = rules(2, 60.0); // trần 2 giờ/ngày: ca 4 giờ trong roster thành vi phạm bắt buộc

        assertCode(() -> workflow.submit(plan.id(), plan.version(), "admin"), PlanException.HARD_VIOLATIONS);
        assertThat(plans.status(plan.id())).isEqualTo("draft");
    }

    @Test
    void exceptionFlow_permissionPreviewConfirmReason_thenPublish() {
        staff.remove(1);
        shifts.remove(1);
        PlanView plan = draft(); // u1 vào s0
        blocked.put("u1", Set.of(day(0))); // sau đó u1 báo không thể làm ngày 0 (vi phạm thuộc nhóm ngoại lệ)

        assertThat(workflow.readiness(plan.id()).missingExceptions()).hasSize(1);
        assertCode(() -> exceptions.previewForPlan(plan.id(), "s0", "u1", null, "boss"), PlanException.FORBIDDEN);

        exceptionRepo.permitted.add("boss");
        Preview preview = exceptions.previewForPlan(plan.id(), "s0", "u1", null, "boss");
        assertThat(preview.allowed()).isTrue();
        assertThat(preview.blocking()).isEmpty();

        assertCode(() -> exceptions.approveForPlan(plan.id(), plan.version(), "s0", "u1", null, "Đổi lịch học",
                preview.previewToken(), false, "boss"), PlanException.CONFIRM_REQUIRED);
        assertCode(() -> exceptions.approveForPlan(plan.id(), plan.version(), "s0", "u1", null, "Đổi lịch học",
                "stale", true, "boss"), PlanException.STALE_PREVIEW);
        assertThatThrownBy(() -> exceptions.approveForPlan(plan.id(), plan.version(), "s0", "u1", null, " ",
                preview.previewToken(), true, "boss")).isInstanceOf(IllegalArgumentException.class);

        exceptions.approveForPlan(plan.id(), plan.version(), "s0", "u1", null, "Đổi lịch học", preview.previewToken(),
                true, "boss");
        assertThat(exceptionRepo.records).singleElement().satisfies(r -> {
            assertThat(r.codes()).containsExactly("UNAVAILABLE");
            assertThat(r.reason()).isEqualTo("Đổi lịch học");
            assertThat(r.approvedBy()).isEqualTo("boss");
        });
        verify(history).writeHistory(eq("schedule_exception"), anyString(), anyString(), eq("boss"), any(), anyString(),
                eq("Đổi lịch học"));

        PlanView current = approved(planService.get(plan.id()));
        workflow.publish(current.id(), current.version(), false, "admin");
        assertThat(official.confirmed()).hasSize(1);
    }

    @Test
    void approvedPlanWithoutTheNeededException_cannotBePublished() {
        PlanView plan = approved(draft());
        blocked.put(plan.roster().get(0).userId(), Set.of(plan.roster().get(0).shiftId().equals("s0") ? day(0) : day(1)));

        assertCode(() -> workflow.publish(plan.id(), plan.version(), false, "admin"), PlanException.EXCEPTION_REQUIRED);
        assertThat(official.confirmed()).isEmpty();
    }

    @Test
    void hardViolationsCanNeverBeOverriddenByAnException() {
        exceptionRepo.permitted.add("boss");
        PlanView plan = draft();
        rules = rules(2, 60.0);
        PlanPair pair = plan.roster().get(0);

        Preview preview = exceptions.previewForPlan(plan.id(), pair.shiftId(), pair.userId(), null, "boss");

        assertThat(preview.allowed()).isFalse();
        assertThat(preview.blocking()).isNotEmpty();
        assertCode(() -> exceptions.approveForPlan(plan.id(), plan.version(), pair.shiftId(), pair.userId(), null,
                "Gấp", preview.previewToken(), true, "boss"), PlanException.HARD_VIOLATIONS);
        assertThat(exceptionRepo.records).isEmpty();
    }

    @Test
    void understaffedShifts_needAnExplicitConfirmation_orAreBlockedByPolicy() {
        staff.remove(1);
        shifts.clear();
        shifts.add(shift("s0", day(0), t(8, 0), t(12, 0), 0, 2, "user"));
        PlanView plan = approved(draft());

        plans.policy = "block";
        assertCode(() -> workflow.publish(plan.id(), plan.version(), true, "admin"), PlanException.UNDERSTAFFED_BLOCKED);
        plans.policy = "warn";
        assertCode(() -> workflow.publish(plan.id(), plan.version(), false, "admin"), PlanException.CONFIRM_UNDERSTAFFED);

        workflow.publish(plan.id(), plan.version(), true, "admin");
        assertThat(official.confirmed()).hasSize(1);
    }

    // ── Phiên bản: công bố bản mới thay bản cũ ─────────────────────────────

    @Test
    void publishingANewVersion_supersedesTheOldOne_andNotifiesRemovedEmployees() {
        staff.remove(1);
        shifts.remove(1);
        PlanView first = approved(draft());
        workflow.publish(first.id(), first.version(), false, "admin"); // u1 vào s0
        events.clear();

        blocked.put("u1", Set.of(day(0)));
        staff.add(employee("u2", "lvl1", 100.0));
        PlanView second = approved(planService.create("p1", "Bản 2", new PlanOptions(List.of("s0"), true), "admin"));
        assertThat(second.roster()).containsExactly(new PlanPair("s0", "u2"));

        PublishReport report = workflow.publish(second.id(), second.version(), false, "admin");

        assertThat(report.supersededPlans()).containsExactly(first.id());
        assertThat(plans.status(first.id())).isEqualTo("superseded");
        assertThat(official.confirmed()).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo("u2"));
        assertThat(report.removed()).isEqualTo(1);
        assertThat(events).singleElement().satisfies(e -> assertThat(e.recipients()).containsExactlyInAnyOrder("u1", "u2"));
    }

    // ── Chỉnh thủ công / kéo thả ───────────────────────────────────────────

    @Test
    void manualAdd_usesTheSameValidation_hardViolationIsRejected() {
        shifts.add(shift("s2", day(0), t(10, 0), t(14, 0), 0, 1, "user")); // trùng giờ với s0
        PlanView plan = planService.create("p1", "P", new PlanOptions(List.of("s0", "s2"), false), "admin");
        PlanPair s0 = plan.roster().stream().filter(p -> p.shiftId().equals("s0")).findFirst().orElseThrow();

        assertCode(() -> edits.add(plan.id(), plan.version(), "s2", s0.userId(), null, "admin"),
                PlanException.HARD_VIOLATIONS);
        assertThat(planService.get(plan.id()).roster()).isEqualTo(plan.roster());
    }

    @Test
    void manualAdd_withOnlyAnExceptionableViolation_requiresTheExceptionFlow() {
        shifts.clear();
        shifts.add(shift("s0", day(0), t(8, 0), t(12, 0), 0, 2, "user"));
        PlanView plan = draft(); // u1 và u2 vào s0
        PlanView removed = edits.remove(plan.id(), plan.version(), "s0", "u2", "admin");
        blocked.put("u2", Set.of(day(0)));

        assertCode(() -> edits.add(plan.id(), removed.version(), "s0", "u2", null, "admin"),
                PlanException.EXCEPTION_REQUIRED);
        assertThat(planService.get(plan.id()).roster()).containsExactly(new PlanPair("s0", "u1"));
    }

    @Test
    void dragAndDrop_movesAnEmployeeBetweenShifts_withOptimisticLocking() {
        staff.remove(1);
        shifts.add(shift("s2", day(2), t(8, 0), t(12, 0), 0, 1, "user"));
        PlanView plan = planService.create("p1", "P", new PlanOptions(List.of("s0", "s2"), false), "admin");
        // u1 có thể vào cả s0 và s2; bỏ khỏi s2 để kéo từ s0 sang s2.
        PlanView afterRemove = edits.remove(plan.id(), plan.version(), "s2", "u1", "admin");

        PlanView moved = edits.move(plan.id(), afterRemove.version(), "u1", "s0", "s2", null, "admin");

        assertThat(moved.roster()).containsExactly(new PlanPair("s2", "u1"));
        assertThat(moved.version()).isEqualTo(afterRemove.version() + 1);
        assertCode(() -> edits.move(plan.id(), afterRemove.version(), "u1", "s2", "s0", null, "admin"),
                PlanException.VERSION_CONFLICT);
    }

    @Test
    void submittedPlansCannotBeEdited() {
        PlanView plan = draft();
        workflow.submit(plan.id(), plan.version(), "admin");
        PlanPair pair = plan.roster().get(0);

        assertCode(() -> edits.remove(plan.id(), plan.version() + 1, pair.shiftId(), pair.userId(), "admin"),
                PlanException.PLAN_NOT_EDITABLE);
    }
}
