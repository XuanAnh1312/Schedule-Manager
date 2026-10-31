package org.dacnpm.schedulemanager.schedule.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.ShiftOutcome;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.PlanException;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanRow;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.Workflow;
import org.dacnpm.schedulemanager.schedule.plan.PlanService;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.AssignmentLine;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.AssignmentsReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.Coverage;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.EmployeeHours;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.HoursReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.HoursTotals;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.PlanVersion;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.ReportContext;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.ShiftLine;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.ShiftsReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.VersionsReport;
import org.dacnpm.schedulemanager.schedule.rules.EmployeeRules;
import org.dacnpm.schedulemanager.schedule.shift.ShiftConfigRules;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.validation.WorkTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dữ liệu báo cáo lịch (TW-07). Chỉ đọc.
 *
 * <ul>
 *   <li>Một báo cáo chỉ có một nguồn: {@code published} (ca đã công bố + phân công chính thức confirmed) hoặc
 *       {@code plan} (roster của đúng một phương án). Nháp không bao giờ bị cộng vào số liệu đã công bố.</li>
 *   <li>Giờ tính bằng cùng hàm với thuật toán phân lịch ({@link WorkTime}, quy tắc hiệu lực), nên cùng ngữ cảnh thì cùng
 *       số với màn hình phân lịch.</li>
 *   <li>Trạng thái workflow (ca: not_scheduled/draft/published; phương án: draft…superseded) và độ phủ
 *       (full/below_target/understaffed) luôn là hai trường riêng.</li>
 * </ul>
 */
@Profile("sqlserver")
@Service
public class ReportService {

    static final String HOURS_BASIS = "Giờ làm = thời lượng ca trừ giờ nghỉ, tách theo ngày lịch; chỉ tính phần nằm "
            + "trong kỳ (ca qua đêm/qua kỳ chỉ tính phần trong kỳ); phân công đã hủy không tính.";
    static final String ROUNDING = "HALF_UP, 2 chữ số thập phân, làm tròn sau khi cộng.";
    static final double NEAR_LIMIT_RATIO = 0.9;
    private static final double EPSILON = 1e-6;
    private static final Set<String> WORKFLOW_STATUSES = Set.of("not_scheduled", "draft", "published");

    /** Bộ lọc dùng chung cho mọi báo cáo; giá trị rỗng là không lọc. */
    public record Filters(String userId, String levelId, String locationId) {
        Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            if (present(userId)) {
                m.put("userId", userId);
            }
            if (present(levelId)) {
                m.put("levelId", levelId);
            }
            if (present(locationId)) {
                m.put("locationId", locationId);
            }
            return m;
        }
    }

    /** Một phân công trong nguồn của báo cáo. assignmentId null với nguồn plan. */
    record Pair(String shiftId, String userId, String assignmentId) {
    }

    /**
     * Dữ liệu đã chọn nguồn của một báo cáo.
     *
     * @param shifts       ca thuộc nguồn (published: ca đã công bố; plan: ca của phương án)
     * @param periodShifts mọi ca chưa hủy của kỳ, không phụ thuộc nguồn
     */
    record Scope(ReportContext context, ScheduleData data, ScheduleContext rules, Map<String, ShiftDetail> details,
                 Map<String, ShiftInfo> shifts, Map<String, ShiftInfo> periodShifts, List<Pair> pairs,
                 EngineResult planResult) {
    }

    private final ScheduleDataRepository data;
    private final ShiftRepository shifts;
    private final PlanRepository plans;
    private final PlanService planService;

    public ReportService(ScheduleDataRepository data, ShiftRepository shifts, PlanRepository plans, PlanService planService) {
        this.data = data;
        this.shifts = shifts;
        this.plans = plans;
        this.planService = planService;
    }

    /** Các phiên bản/phương án của kỳ: trạng thái workflow, version, độ phủ và tổng giờ của từng bản. */
    @Transactional(readOnly = true)
    public VersionsReport versions(String periodId) {
        Scope published = scope(periodId, ReportModels.SOURCE_PUBLISHED, null, new Filters(null, null, null));
        List<PlanVersion> versions = new ArrayList<>();
        for (PlanRow row : plans.listByPeriod(published.context().periodId())) {
            PlanView plan = planService.get(row.id());
            Workflow wf = plans.workflow(row.id()).orElse(null);
            List<Pair> roster = plan.roster().stream().map(p -> new Pair(p.shiftId(), p.userId(), null)).toList();
            Map<String, ShiftInfo> planShifts = new LinkedHashMap<>();
            // Phương án có thể gồm cả ca chưa công bố: độ phủ tính trên mọi ca của kỳ, không chỉ ca đã công bố.
            plan.shiftIds().stream().filter(published.periodShifts()::containsKey)
                    .forEach(id -> planShifts.put(id, published.periodShifts().get(id)));
            double hours = roster.stream().filter(p -> planShifts.containsKey(p.shiftId()))
                    .mapToDouble(p -> hoursInPeriod(planShifts.get(p.shiftId()), published.data().period())).sum();
            versions.add(new PlanVersion(row.id(), row.name(), row.status(), row.version(), row.runCount(),
                    row.createdAt(), row.updatedAt(), wf == null ? null : wf.publishedAt(),
                    wf == null ? null : wf.supersededBy(), roster.size(), round(hours), coverage(planShifts, roster)));
        }
        return new VersionsReport(published.context(), coverage(published.shifts(), published.pairs()), versions);
    }

    /** Tổng giờ theo nhân viên: giờ, mục tiêu, tuần cao nhất, trạng thái gần/vượt giới hạn. */
    @Transactional(readOnly = true)
    public HoursReport hours(String periodId, String source, String planId, Filters filters) {
        Scope s = scope(periodId, source, planId, filters);
        Period period = s.data().period();
        List<EmployeeHours> rows = new ArrayList<>();
        for (Employee e : s.data().employees()) {
            if (!matchesEmployee(e, filters)) {
                continue;
            }
            List<Pair> mine = s.pairs().stream()
                    .filter(p -> p.userId().equals(e.id()) && matchesLocation(s.shifts().get(p.shiftId()), filters))
                    .toList();
            if (!e.active() && mine.isEmpty()) {
                continue;
            }
            Map<LocalDate, Double> byWeek = new TreeMap<>();
            double minutes = 0;
            for (Pair p : mine) {
                for (Map.Entry<LocalDate, Double> day : WorkTime.workMinutesByDay(s.shifts().get(p.shiftId())).entrySet()) {
                    if (period.contains(day.getKey())) {
                        minutes += day.getValue();
                        byWeek.merge(WorkTime.weekStart(day.getKey()), day.getValue() / 60.0, Double::sum);
                    }
                }
            }
            double hours = minutes / 60.0;
            EmployeeRules rules = s.rules().rulesFor(e, period.startDate());
            double peak = byWeek.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            Double target = rules.targetHours();
            Map<LocalDate, Double> roundedWeeks = new TreeMap<>();
            byWeek.forEach((week, h) -> roundedWeeks.put(week, round(h)));
            rows.add(new EmployeeHours(e.id(), e.label(), e.levelId(), mine.size(), round(hours),
                    target == null ? null : round(target),
                    target == null || target <= 0 ? null : round(hours / target * 100), round(peak),
                    rules.maxHoursPerWeek(), rules.maxHoursPerPeriod(), rules.maxHoursFormula(),
                    limitStatus(peak, rules.maxHoursPerWeek(), hours, rules.maxHoursPerPeriod()), roundedWeeks));
        }
        rows.sort(Comparator.comparing(EmployeeHours::fullName, Comparator.nullsLast(String::compareTo))
                .thenComparing(EmployeeHours::userId));
        HoursTotals totals = new HoursTotals(rows.size(), rows.stream().mapToInt(EmployeeHours::shifts).sum(),
                round(rows.stream().mapToDouble(EmployeeHours::hours).sum()),
                round(rows.stream().filter(r -> r.targetHours() != null).mapToDouble(EmployeeHours::targetHours).sum()),
                (int) rows.stream().filter(r -> "over_limit".equals(r.limitStatus())).count(),
                (int) rows.stream().filter(r -> "near_limit".equals(r.limitStatus())
                        || "at_limit".equals(r.limitStatus())).count(),
                (int) rows.stream().filter(r -> r.targetHours() != null && r.hours() + EPSILON < r.targetHours()).count());
        return new HoursReport(s.context(), totals, rows);
    }

    /** Danh sách phẳng các phân công (dùng cho bảng/CSV). */
    @Transactional(readOnly = true)
    public AssignmentsReport assignments(String periodId, String source, String planId, Filters filters) {
        Scope s = scope(periodId, source, planId, filters);
        Map<String, Employee> employees = s.data().employees().stream()
                .collect(Collectors.toMap(Employee::id, e -> e, (a, b) -> a));
        Map<String, Long> filledByShift = s.pairs().stream()
                .collect(Collectors.groupingBy(Pair::shiftId, Collectors.counting()));
        List<AssignmentLine> lines = new ArrayList<>();
        for (Pair p : s.pairs()) {
            ShiftInfo shift = s.shifts().get(p.shiftId());
            Employee e = employees.get(p.userId());
            if (!matchesLocation(shift, filters) || (e != null && !matchesEmployee(e, filters))
                    || (e == null && (present(filters.userId()) || present(filters.levelId())))) {
                continue;
            }
            int filled = filledByShift.getOrDefault(p.shiftId(), 0L).intValue();
            lines.add(new AssignmentLine(shift.id(), shift.name(), shift.date(), shift.startTime(), shift.endTime(),
                    shift.breakMinutes(), shift.locationId(), p.userId(), e == null ? p.userId() : e.label(),
                    e == null ? null : e.levelId(), round(hoursInPeriod(shift, s.data().period())),
                    workflowStatus(s.details().get(shift.id())), coverageOf(shift, filled), p.assignmentId()));
        }
        lines.sort(Comparator.comparing(AssignmentLine::date).thenComparing(AssignmentLine::startTime)
                .thenComparing(AssignmentLine::shiftId).thenComparing(AssignmentLine::fullName));
        double total = lines.stream().mapToDouble(AssignmentLine::hoursInPeriod).sum();
        return new AssignmentsReport(s.context(), lines.size(), round(total), lines);
    }

    /** Độ phủ từng ca; với nguồn plan kèm lý do thiếu người từ lần chạy gần nhất của phương án. */
    @Transactional(readOnly = true)
    public ShiftsReport shifts(String periodId, String source, String planId, Filters filters) {
        Scope s = scope(periodId, source, planId, filters);
        Map<String, Long> filledByShift = s.pairs().stream()
                .collect(Collectors.groupingBy(Pair::shiftId, Collectors.counting()));
        Map<String, ShiftOutcome> outcomes = new HashMap<>();
        if (s.planResult() != null) {
            s.planResult().shifts().forEach(o -> outcomes.put(o.shiftId(), o));
        }
        List<ShiftLine> lines = new ArrayList<>();
        Map<String, ShiftInfo> included = new LinkedHashMap<>();
        for (ShiftInfo shift : s.shifts().values()) {
            if (!matchesLocation(shift, filters)) {
                continue;
            }
            included.put(shift.id(), shift);
            int filled = filledByShift.getOrDefault(shift.id(), 0L).intValue();
            String coverage = coverageOf(shift, filled);
            lines.add(new ShiftLine(shift.id(), shift.name(), shift.date(), shift.startTime(), shift.endTime(),
                    shift.locationId(), workflowStatus(s.details().get(shift.id())), coverage, shift.totalRequired(),
                    shift.minimum(), filled, Math.max(0, shift.totalRequired() - filled),
                    EngineResult.FULL.equals(coverage) ? List.of() : reasons(outcomes.get(shift.id()))));
        }
        lines.sort(Comparator.comparing(ShiftLine::date).thenComparing(ShiftLine::startTime).thenComparing(ShiftLine::shiftId));
        return new ShiftsReport(s.context(), coverage(included, s.pairs()), lines);
    }

    Scope scope(String periodId, String source, String planId, Filters filters) {
        String src = present(source) ? source : (present(planId) ? ReportModels.SOURCE_PLAN : ReportModels.SOURCE_PUBLISHED);
        if (!ReportModels.SOURCE_PUBLISHED.equals(src) && !ReportModels.SOURCE_PLAN.equals(src)) {
            throw new IllegalArgumentException("source phải là published hoặc plan.");
        }
        PlanView plan = null;
        if (ReportModels.SOURCE_PLAN.equals(src)) {
            if (!present(planId)) {
                throw new IllegalArgumentException("Nguồn plan cần planId.");
            }
            plan = planService.get(planId);
            if (present(periodId) && !periodId.equals(plan.periodId())) {
                throw new IllegalArgumentException("Phương án " + planId + " không thuộc kỳ " + periodId + ".");
            }
            periodId = plan.periodId();
        } else if (present(planId)) {
            throw new IllegalArgumentException("Nguồn published không nhận planId; dùng source=plan để xem phương án.");
        }
        if (!present(periodId)) {
            throw new IllegalArgumentException("Thiếu thông số periodId.");
        }
        String pid = periodId;
        ScheduleData d = data.load(pid).orElseThrow(() -> PlanException.notFound("Không tìm thấy kỳ " + pid + "."));
        Period period = d.period();

        Map<String, ShiftDetail> details = new LinkedHashMap<>();
        Map<String, ShiftInfo> inPeriod = new LinkedHashMap<>();
        for (ShiftDetail detail : shifts.search(period.startDate(), period.endDate(), null, false)) {
            details.put(detail.id(), detail);
            inPeriod.put(detail.id(), ShiftConfigRules.toShiftInfo(detail.id(), detail.shift()));
        }

        Map<String, ShiftInfo> scoped = new LinkedHashMap<>();
        List<Pair> pairs = new ArrayList<>();
        if (plan == null) {
            inPeriod.forEach((id, shift) -> {
                if ("published".equals(details.get(id).status())) {
                    scoped.put(id, shift);
                }
            });
            for (Assignment a : d.savedAssignments()) {
                if (a.active() && scoped.containsKey(a.shiftId())) {
                    pairs.add(new Pair(a.shiftId(), a.userId(), a.id()));
                }
            }
        } else {
            for (String id : plan.shiftIds()) {
                if (inPeriod.containsKey(id)) {
                    scoped.put(id, inPeriod.get(id));
                }
            }
            for (PlanPair p : plan.roster()) {
                if (scoped.containsKey(p.shiftId())) {
                    pairs.add(new Pair(p.shiftId(), p.userId(), null));
                }
            }
        }

        ReportContext context = new ReportContext(period.id(), period.name(),
                plans.periodStatus(period.id()).orElse(null), period.startDate(), period.endDate(), src,
                plan == null ? null : plan.id(), plan == null ? null : plan.status(),
                plan == null ? null : plan.version(), (filters == null ? new Filters(null, null, null) : filters).asMap(),
                Instant.now(), HOURS_BASIS, ROUNDING,
                d.effectiveRules() == null ? "schedule_rules" : "effective_rules");
        return new Scope(context, d, d.context(Set.of()), details, scoped, inPeriod, pairs,
                plan == null ? null : plan.result());
    }

    static Coverage coverage(Map<String, ShiftInfo> shifts, List<Pair> pairs) {
        Map<String, Long> filled = pairs.stream().collect(Collectors.groupingBy(Pair::shiftId, Collectors.counting()));
        int full = 0;
        int below = 0;
        int under = 0;
        int required = 0;
        int filledPositions = 0;
        for (ShiftInfo shift : shifts.values()) {
            int n = filled.getOrDefault(shift.id(), 0L).intValue();
            required += shift.totalRequired();
            filledPositions += Math.min(n, shift.totalRequired());
            switch (coverageOf(shift, n)) {
                case EngineResult.FULL -> full++;
                case EngineResult.BELOW_TARGET -> below++;
                default -> under++;
            }
        }
        return new Coverage(shifts.size(), full, below, under, required, filledPositions);
    }

    static String coverageOf(ShiftInfo shift, int filled) {
        return filled >= shift.totalRequired() ? EngineResult.FULL
                : shift.understaffedWith(filled) ? EngineResult.UNDERSTAFFED : EngineResult.BELOW_TARGET;
    }

    /** Trạng thái workflow của ca. Giá trị cũ full/understaffed (V5) là độ phủ chứ không phải workflow: coi là draft. */
    static String workflowStatus(ShiftDetail detail) {
        if (detail == null) {
            return null;
        }
        return WORKFLOW_STATUSES.contains(detail.status()) ? detail.status() : "draft";
    }

    static String limitStatus(double peakWeek, double maxWeek, double hours, Double maxPeriod) {
        boolean over = peakWeek > maxWeek + EPSILON || (maxPeriod != null && hours > maxPeriod + EPSILON);
        if (over) {
            return "over_limit";
        }
        boolean at = Math.abs(peakWeek - maxWeek) < EPSILON || (maxPeriod != null && Math.abs(hours - maxPeriod) < EPSILON);
        if (at) {
            return "at_limit";
        }
        boolean near = peakWeek >= maxWeek * NEAR_LIMIT_RATIO || (maxPeriod != null && hours >= maxPeriod * NEAR_LIMIT_RATIO);
        return near ? "near_limit" : "ok";
    }

    private static List<String> reasons(ShiftOutcome outcome) {
        if (outcome == null) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>(outcome.warnings());
        Map<String, Long> codes = new TreeMap<>();
        outcome.rejections().forEach(r -> r.violations().forEach(v -> codes.merge(v.code().name(), 1L, Long::sum)));
        codes.forEach((code, n) -> reasons.add("Ứng viên bị loại vì " + code + ": " + n));
        return reasons;
    }

    private static double hoursInPeriod(ShiftInfo shift, Period period) {
        return WorkTime.workMinutesByDay(shift).entrySet().stream()
                .filter(e -> period.contains(e.getKey()))
                .mapToDouble(Map.Entry::getValue).sum() / 60.0;
    }

    private static boolean matchesEmployee(Employee e, Filters f) {
        return f == null || ((!present(f.userId()) || f.userId().equals(e.id()))
                && (!present(f.levelId()) || f.levelId().equals(e.levelId())));
    }

    private static boolean matchesLocation(ShiftInfo shift, Filters f) {
        return shift != null && (f == null || !present(f.locationId()) || f.locationId().equals(shift.locationId()));
    }

    static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
