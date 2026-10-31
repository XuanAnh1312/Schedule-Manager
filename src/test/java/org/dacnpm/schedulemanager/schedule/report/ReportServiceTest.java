package org.dacnpm.schedulemanager.schedule.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.EmployeeLoad;
import org.dacnpm.schedulemanager.schedule.engine.ScheduleEngine;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.FakePlanRepository;
import org.dacnpm.schedulemanager.schedule.plan.PlanService;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanOptions;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.AssignmentsReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.EmployeeHours;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.HoursReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.ShiftLine;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.ShiftsReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.VersionsReport;
import org.dacnpm.schedulemanager.schedule.report.ReportService.Filters;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRequest;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TW-07: báo cáo có ngữ cảnh chuẩn, không gộp nháp với công bố, workflow tách khỏi độ phủ, cùng số với phân lịch. */
class ReportServiceTest {

    private static final Filters NONE = new Filters(null, null, null);

    private final FakePlanRepository plans = new FakePlanRepository();
    private final ScheduleDataRepository data = mock(ScheduleDataRepository.class);
    private final ShiftRepository shifts = mock(ShiftRepository.class);
    private PlanService planService;
    private ReportService reports;

    private final List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl2", 100.0));
    private final List<ShiftInfo> infos = List.of(
            shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user"),       // đã công bố, u1
            shift("s1", day(1), t(8, 0), t(12, 30), 30, 1, "user"),     // nháp, u2 có phân công chính thức chưa công bố
            shift("s2", day(27), t(22, 0), t(6, 0), 0, 2, "user"));     // đã công bố, ngày cuối kỳ, qua đêm
    private final Map<String, String> status = Map.of("s0", "published", "s1", "draft", "s2", "published");
    private List<Assignment> saved = new ArrayList<>(List.of(
            saved("a0", "s0", "u1"),
            saved("a1", "s1", "u2"),
            saved("a2", "s2", "u1"),
            new Assignment("a3", "s0", "u2", Assignment.CANCELLED)));

    private Map<String, java.util.Set<java.time.LocalDate>> blocked = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        planService = new PlanService(plans, data, new ScheduleEngine(new AssignmentValidator()), shifts,
                new ObjectMapper().findAndRegisterModules());
        reports = new ReportService(data, shifts, plans, planService);
        plans.setPeriodStatus("p1", "locked");
        when(data.load("p1")).thenAnswer(inv -> Optional.of(new ScheduleData(PERIOD, rules(12, 60.0), staff, infos,
                List.of("s0", "s2"), freeAllPeriod(staff), saved, blocked)));
        List<ShiftDetail> details = new ArrayList<>();
        for (ShiftInfo s : infos) {
            ShiftRequest r = new ShiftRequest(s.name(), s.date(), s.startTime(), s.endTime(), s.breakMinutes(),
                    s.id().equals("s2") ? "loc2" : "loc1", "user", s.totalRequired(), null, null, null, null, null, null,
                    null, null, null);
            details.add(new ShiftDetail(s.id(), r, status.get(s.id()), 1, false, null, null, null, null));
        }
        when(shifts.search(any(), any(), any(), anyBoolean())).thenReturn(details);
    }

    private static EmployeeHours row(HoursReport report, String userId) {
        return report.employees().stream().filter(e -> e.userId().equals(userId)).findFirst().orElseThrow();
    }

    @Test
    void publishedReport_excludesDraftShiftsAndCancelledAssignments() {
        HoursReport hours = reports.hours("p1", null, null, NONE);

        assertThat(hours.context().source()).isEqualTo("published");
        assertThat(hours.context().planId()).isNull();
        // u1: s0 4h + 2h của ca đêm ngày cuối kỳ (phần sau nửa đêm nằm ngoài kỳ).
        assertThat(row(hours, "u1").hours()).isEqualTo(6.0);
        assertThat(row(hours, "u1").shifts()).isEqualTo(2);
        // u2: phân công trên ca nháp và phân công đã hủy đều không tính.
        assertThat(row(hours, "u2").hours()).isZero();

        AssignmentsReport lines = reports.assignments("p1", "published", null, NONE);
        assertThat(lines.lines()).extracting(l -> l.assignmentId()).containsExactly("a0", "a2");
        assertThat(lines.totalHours()).isEqualTo(6.0);
    }

    @Test
    void sameContext_sameNumbersAsTheScheduler() {
        PlanView plan = planService.create("p1", "Bản đầy đủ", new PlanOptions(List.of("s0", "s1", "s2"), true), "admin");

        HoursReport report = reports.hours(null, "plan", plan.id(), NONE);

        for (EmployeeLoad load : plan.result().employees()) {
            assertThat(row(report, load.userId()).hours())
                    .as(load.userId())
                    .isEqualTo(ReportService.round(load.savedHours() + load.plannedHours()));
        }
        assertThat(report.context().source()).isEqualTo("plan");
        assertThat(report.context().planStatus()).isEqualTo("draft");
        assertThat(report.context().planVersion()).isEqualTo(plan.version());
    }

    @Test
    void draftPlanIsNeverMixedIntoThePublishedReport() {
        double before = reports.hours("p1", "published", null, NONE).totals().hours();
        planService.create("p1", "Nháp", new PlanOptions(List.of("s0", "s1", "s2"), true), "admin");

        assertThat(reports.hours("p1", "published", null, NONE).totals().hours()).isEqualTo(before);
    }

    @Test
    void workflowStatusAndCoverageAreSeparateFields() {
        ShiftsReport published = reports.shifts("p1", null, null, NONE);

        ShiftLine s0 = published.shifts().stream().filter(l -> l.shiftId().equals("s0")).findFirst().orElseThrow();
        assertThat(s0.workflowStatus()).isEqualTo("published");
        assertThat(s0.coverage()).isEqualTo("full");
        ShiftLine s2 = published.shifts().stream().filter(l -> l.shiftId().equals("s2")).findFirst().orElseThrow();
        assertThat(s2.workflowStatus()).isEqualTo("published");
        assertThat(s2.coverage()).isEqualTo("understaffed");
        assertThat(s2.missing()).isEqualTo(1);
        assertThat(published.shifts()).noneMatch(l -> l.shiftId().equals("s1"));
        assertThat(published.coverage().full()).isEqualTo(1);
        assertThat(published.coverage().understaffed()).isEqualTo(1);

        ShiftDetail legacy = new ShiftDetail("x", null, "understaffed", 1, false, null, null, null, null);
        assertThat(ReportService.workflowStatus(legacy)).isEqualTo("draft");
    }

    @Test
    void planShiftsReport_explainsWhyAShiftIsUnderstaffed() {
        blocked.put("u2", java.util.Set.of(day(27))); // ca s2 cần 2 người, chỉ còn u1 làm được
        PlanView plan = planService.create("p1", "P", new PlanOptions(List.of("s2"), true), "admin");

        ShiftLine s2 = reports.shifts(null, "plan", plan.id(), NONE).shifts().get(0);

        assertThat(s2.coverage()).isEqualTo("understaffed");
        assertThat(s2.reasons()).anyMatch(r -> r.contains("UNAVAILABLE"));
    }

    @Test
    void versionsReport_listsPlansWithWorkflowAndCoverageSeparately() {
        PlanView plan = planService.create("p1", "Bản 1", new PlanOptions(List.of("s0", "s1", "s2"), true), "admin");

        VersionsReport report = reports.versions("p1");

        assertThat(report.versions()).singleElement().satisfies(v -> {
            assertThat(v.planId()).isEqualTo(plan.id());
            assertThat(v.workflowStatus()).isEqualTo("draft");
            assertThat(v.version()).isEqualTo(plan.version());
            assertThat(v.coverage().totalShifts()).isEqualTo(3);
            assertThat(v.assignments()).isEqualTo(plan.roster().size());
        });
        assertThat(report.publishedCoverage().totalShifts()).isEqualTo(2);
    }

    @Test
    void filtersApplyToEveryReportAndAreEchoedInTheContext() {
        HoursReport byLevel = reports.hours("p1", null, null, new Filters(null, "lvl2", null));
        assertThat(byLevel.employees()).extracting(EmployeeHours::userId).containsExactly("u2");
        assertThat(byLevel.context().filters()).containsEntry("levelId", "lvl2");

        AssignmentsReport byLocation = reports.assignments("p1", null, null, new Filters(null, null, "loc2"));
        assertThat(byLocation.lines()).extracting(l -> l.shiftId()).containsExactly("s2");
    }

    @Test
    void invalidSourceCombinationsAreRejected() {
        assertThatThrownBy(() -> reports.hours("p1", "plan", null, NONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.hours("p1", "published", "plan_x", NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.hours("p1", "everything", null, NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.hours(null, null, null, NONE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitStatus_nearAtAndOverTheCeiling() {
        assertThat(ReportService.limitStatus(40, 60, 40, null)).isEqualTo("ok");
        assertThat(ReportService.limitStatus(55, 60, 55, null)).isEqualTo("near_limit");
        assertThat(ReportService.limitStatus(60, 60, 60, null)).isEqualTo("at_limit");
        assertThat(ReportService.limitStatus(61, 60, 61, null)).isEqualTo("over_limit");
        assertThat(ReportService.limitStatus(10, 60, 81, 80.0)).isEqualTo("over_limit");
    }
}
