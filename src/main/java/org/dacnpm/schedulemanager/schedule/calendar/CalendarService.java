package org.dacnpm.schedulemanager.schedule.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarRepository.AssignmentRow;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarRepository.Person;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarRepository.PublishedVersion;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.PlanException;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanService;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.shift.ShiftConfigRules;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.validation.WorkTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lịch ngày/tuần/tháng (TW-05).
 *
 * <ul>
 *   <li>Admin: lịch chính thức, hoặc xem phương án nháp chồng lên (planId) — ca thuộc phương án hiển thị roster của
 *       phương án. Lọc theo kỳ, địa điểm (chi nhánh), nhân viên, Level, trạng thái công bố hoặc mức đủ người.</li>
 *   <li>Nhân viên: chỉ ca đã công bố có phân công confirmed của chính mình; không bao giờ thấy nháp hay ca của người
 *       khác. Kèm các phiên bản lịch đã công bố và trạng thái đã xem/xác nhận.</li>
 * </ul>
 */
@Profile("sqlserver")
@Service
public class CalendarService {

    static final int MAX_DAYS = 93;
    static final Set<String> VIEWS = Set.of("day", "week", "month");

    /**
     * @param view     day, week hoặc month quanh {@code date}; bỏ qua khi có from/to
     * @param status   trạng thái ca (not_scheduled, draft, published) hoặc mức đủ người (full, below_target, understaffed)
     * @param planId   xem phương án chồng lên lịch chính thức
     */
    public record Query(String view, LocalDate date, LocalDate from, LocalDate to, String periodId, String locationId,
                        String userId, String levelId, String status, String planId, String groupId) {
        public Query(String view,LocalDate date,LocalDate from,LocalDate to,String periodId,String locationId,
                String userId,String levelId,String status,String planId) {
            this(view,date,from,to,periodId,locationId,userId,levelId,status,planId,null);
        }
    }

    public record Assignee(String userId, String fullName, String levelId, String assignmentId, Integer version) {
    }

    /** source: official hoặc id phương án. */
    public record CalendarShift(String shiftId, String name, LocalDate date, LocalTime startTime, LocalTime endTime,
                                String locationId, String status, String coverage, int required, int minimum,
                                int filled, boolean emergency, String source, List<Assignee> assignees) {
    }

    public record CalendarView(LocalDate from, LocalDate to, String planId, List<CalendarShift> shifts) {
    }

    public record MyShift(String shiftId, String name, LocalDate date, LocalTime startTime, LocalTime endTime,
                          int breakMinutes, String locationId, double hours, String assignmentId) {
    }

    /** @param needsConfirmation còn phiên bản đã công bố chưa xác nhận */
    public record MySchedule(LocalDate from, LocalDate to, List<MyShift> shifts, double totalHours,
                             List<PublishedVersion> versions, boolean needsConfirmation) {
    }

    private final ShiftRepository shifts;
    private final CalendarRepository repository;
    private final PlanService plans;

    public CalendarService(ShiftRepository shifts, CalendarRepository repository, PlanService plans) {
        this.shifts = shifts;
        this.repository = repository;
        this.plans = plans;
    }

    @Transactional(readOnly = true)
    public CalendarView calendar(Query q) {
        LocalDate[] range = range(q.view(), q.date(), q.from(), q.to());
        LocalDate from = range[0];
        LocalDate to = range[1];
        if (q.periodId() != null && !q.periodId().isBlank()) {
            Period period = repository.period(q.periodId())
                    .orElseThrow(() -> PlanException.notFound("Không tìm thấy kỳ " + q.periodId() + "."));
            from = from.isBefore(period.startDate()) ? period.startDate() : from;
            to = to.isAfter(period.endDate()) ? period.endDate() : to;
            if (from.isAfter(to)) {
                return new CalendarView(from, to, q.planId(), List.of());
            }
        }

        Map<String, List<Assignee>> byShift = new HashMap<>();
        for (AssignmentRow row : repository.confirmedAssignments(from, to)) {
            byShift.computeIfAbsent(row.shiftId(), k -> new ArrayList<>()).add(new Assignee(row.userId(), row.fullName(),
                    row.levelId(), row.assignmentId(), row.version()));
        }
        Set<String> planShifts = new HashSet<>();
        if (q.planId() != null && !q.planId().isBlank()) {
            PlanView plan = plans.get(q.planId());
            planShifts.addAll(plan.shiftIds());
            Map<String, Person> people = repository.people(plan.roster().stream().map(PlanPair::userId).distinct().toList());
            planShifts.forEach(byShift::remove);
            for (PlanPair pair : plan.roster()) {
                Person p = people.get(pair.userId());
                byShift.computeIfAbsent(pair.shiftId(), k -> new ArrayList<>()).add(new Assignee(pair.userId(),
                        p == null ? pair.userId() : p.fullName(), p == null ? null : p.levelId(), null, null));
            }
        }

        Set<String> groupMembers = q.groupId()==null || q.groupId().isBlank() ? null : repository.groupMembers(q.groupId());
        List<CalendarShift> result = new ArrayList<>();
        for (ShiftDetail detail : shifts.search(from, to, blankToNull(q.locationId()), false)) {
            if(q.periodId()!=null&&!q.periodId().isBlank()&&detail.shift().periodId()!=null&&!q.periodId().equals(detail.shift().periodId()))continue;
            List<Assignee> assignees = byShift.getOrDefault(detail.id(), List.of());
            ShiftInfo info = ShiftConfigRules.toShiftInfo(detail.id(), detail.shift());
            int filled = assignees.size();
            String coverage = filled >= info.totalRequired() ? EngineResult.FULL
                    : info.understaffedWith(filled) ? EngineResult.UNDERSTAFFED : EngineResult.BELOW_TARGET;
            CalendarShift entry = new CalendarShift(detail.id(), info.name(), info.date(), info.startTime(),
                    info.endTime(), info.locationId(), detail.status(), coverage, info.totalRequired(), info.minimum(),
                    filled, detail.emergency(), planShifts.contains(detail.id()) ? q.planId() : "official", assignees);
            if (matches(entry, info, q) && (groupMembers==null || assignees.stream().anyMatch(a->groupMembers.contains(a.userId())))) {
                result.add(entry);
            }
        }
        return new CalendarView(from, to, blankToNull(q.planId()), result);
    }

    /** Lịch chính thức của chính nhân viên: chỉ ca đã công bố, chỉ phân công confirmed của người đó. */
    @Transactional(readOnly = true)
    public MySchedule mine(String userId, String view, LocalDate date, LocalDate fromDate, LocalDate toDate) {
        LocalDate[] range = range(view, date, fromDate, toDate);
        Map<String, String> mine = new HashMap<>();
        for (AssignmentRow row : repository.confirmedAssignments(range[0], range[1])) {
            if (row.userId().equals(userId)) {
                mine.put(row.shiftId(), row.assignmentId());
            }
        }
        List<MyShift> result = new ArrayList<>();
        double total = 0;
        for (ShiftDetail detail : shifts.search(range[0], range[1], null, false)) {
            if (!"published".equals(detail.status()) || !mine.containsKey(detail.id())) {
                continue;
            }
            ShiftInfo info = ShiftConfigRules.toShiftInfo(detail.id(), detail.shift());
            double hours = WorkTime.workMinutesByDay(info).values().stream().mapToDouble(Double::doubleValue).sum() / 60.0;
            total += hours;
            result.add(new MyShift(detail.id(), info.name(), info.date(), info.startTime(), info.endTime(),
                    info.breakMinutes(), info.locationId(), Math.round(hours * 100) / 100.0, mine.get(detail.id())));
        }
        List<PublishedVersion> versions = repository.publishedVersionsOf(userId, range[0], range[1]);
        boolean pending = versions.stream().anyMatch(v -> v.confirmedAt() == null);
        return new MySchedule(range[0], range[1], result, Math.round(total * 100) / 100.0, versions, pending);
    }

    /** Ghi nhận đã xem (confirmed = false) hoặc đã xác nhận phiên bản lịch đã công bố của chính mình. */
    @Transactional
    public void acknowledge(String userId, String planId, boolean confirmed) {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("Thiếu thông số planId.");
        }
        if (!repository.isPublishedFor(planId, userId)) {
            throw PlanException.notFound("Không có lịch đã công bố " + planId + " dành cho bạn.");
        }
        repository.acknowledge(userId, planId, confirmed);
    }

    static LocalDate[] range(String view, LocalDate date, LocalDate from, LocalDate to) {
        if (from != null || to != null) {
            if (from == null || to == null || to.isBefore(from)) {
                throw new IllegalArgumentException("Khoảng ngày không hợp lệ: cần cả from và to, to không trước from.");
            }
            if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
                throw new IllegalArgumentException("Khoảng ngày tối đa " + MAX_DAYS + " ngày.");
            }
            return new LocalDate[] {from, to};
        }
        String v = view == null || view.isBlank() ? "week" : view;
        if (!VIEWS.contains(v)) {
            throw new IllegalArgumentException("view phải là day, week hoặc month.");
        }
        LocalDate d = date == null ? LocalDate.now() : date;
        return switch (v) {
            case "day" -> new LocalDate[] {d, d};
            case "month" -> new LocalDate[] {d.withDayOfMonth(1), d.with(TemporalAdjusters.lastDayOfMonth())};
            default -> {
                LocalDate monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new LocalDate[] {monday, monday.plusDays(6)};
            }
        };
    }

    private static boolean matches(CalendarShift entry, ShiftInfo info, Query q) {
        if (q.userId() != null && !q.userId().isBlank()
                && entry.assignees().stream().noneMatch(a -> a.userId().equals(q.userId()))) {
            return false;
        }
        if (q.levelId() != null && !q.levelId().isBlank()
                && !info.levelRequirements().containsKey(q.levelId())
                && entry.assignees().stream().noneMatch(a -> q.levelId().equals(a.levelId()))) {
            return false;
        }
        if (q.status() != null && !q.status().isBlank()) {
            return q.status().equals(entry.status()) || q.status().equals(entry.coverage());
        }
        return true;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
