package org.dacnpm.schedulemanager.schedule.plan;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.rules.EffectiveRules;
import org.dacnpm.schedulemanager.schedule.rules.EffectiveRulesSource;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.model.TimeRange;
import org.dacnpm.schedulemanager.schedule.model.UserAvailability;
import org.dacnpm.schedulemanager.schedule.shift.ShiftConfigRules;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Nạp toàn bộ dữ liệu một kỳ cần cho kiểm tra phân công: quy tắc, nhân viên, ca, thời gian rảnh,
 * ngày không thể làm và phân công đã lưu. Chỉ đọc.
 *
 * <p>Ca và phân công được nạp từ thứ hai của tuần đầu đến chủ nhật của tuần cuối (cộng một ngày đệm) để
 * kiểm tra trùng giờ, nghỉ tối thiểu và giờ/tuần ở tuần giáp ranh kỳ.
 */
@Profile("sqlserver")
@Repository
public class ScheduleDataRepository {

    private static final String MAIN_RULES = "rules_main";
    private static final TimeRange WHOLE_DAY = new TimeRange(0, 1440);

    /**
     * @param publishedShiftIds ca đã công bố trong kỳ
     * @param availability      userId -> đăng ký thời gian rảnh
     * @param blockedDates      userId -> các ngày không thể làm
     * @param effectiveRules    quy tắc hiệu lực theo nhân viên/ngày; null là suy ra từ {@code rules}
     */
    public record ScheduleData(
            Period period,
            ScheduleRules rules,
            List<Employee> employees,
            List<ShiftInfo> shifts,
            List<String> publishedShiftIds,
            Map<String, UserAvailability> availability,
            List<Assignment> savedAssignments,
            Map<String, Set<LocalDate>> blockedDates,
            EffectiveRules effectiveRules,
            Map<String,String> shiftPeriodIds) {

        public ScheduleData(Period period,ScheduleRules rules,List<Employee> employees,List<ShiftInfo> shifts,
                List<String> publishedShiftIds,Map<String,UserAvailability> availability,List<Assignment> savedAssignments,
                Map<String,Set<LocalDate>> blockedDates,EffectiveRules effectiveRules) {
            this(period,rules,employees,shifts,publishedShiftIds,availability,savedAssignments,blockedDates,effectiveRules,Map.of());
        }

        /** Chưa có quy tắc hiệu lực riêng: suy ra từ schedule_rules. */
        public ScheduleData(Period period, ScheduleRules rules, List<Employee> employees, List<ShiftInfo> shifts,
                            List<String> publishedShiftIds, Map<String, UserAvailability> availability,
                            List<Assignment> savedAssignments, Map<String, Set<LocalDate>> blockedDates) {
            this(period, rules, employees, shifts, publishedShiftIds, availability, savedAssignments, blockedDates, null);
        }

        /** Ngữ cảnh kiểm tra/phân lịch; replacedAssignmentIds là phân công đã lưu đang được thay thế. */
        public ScheduleContext context(Set<String> replacedAssignmentIds) {
            return new ScheduleContext(period, rules, employees, shifts, availability, blockedDates, savedAssignments,
                    replacedAssignmentIds, effectiveRules);
        }

        public ScheduleData {
            shiftPeriodIds=shiftPeriodIds==null?Map.of():Map.copyOf(shiftPeriodIds);
            employees = employees == null ? List.of() : List.copyOf(employees);
            shifts = shifts == null ? List.of() : List.copyOf(shifts);
            publishedShiftIds = publishedShiftIds == null ? List.of() : List.copyOf(publishedShiftIds);
            availability = availability == null ? Map.of() : Map.copyOf(availability);
            savedAssignments = savedAssignments == null ? List.of() : List.copyOf(savedAssignments);
            blockedDates = blockedDates == null ? Map.of() : Map.copyOf(blockedDates);
        }

        /** Không có ngày không thể làm. */
        public ScheduleData(Period period, ScheduleRules rules, List<Employee> employees, List<ShiftInfo> shifts,
                            List<String> publishedShiftIds, Map<String, UserAvailability> availability,
                            List<Assignment> savedAssignments) {
            this(period, rules, employees, shifts, publishedShiftIds, availability, savedAssignments, Map.of());
        }

        /** Ca thuộc kỳ và chưa công bố. */
        public List<String> plannableShiftIds() {
            Set<String> published = Set.copyOf(publishedShiftIds);
            return shifts.stream()
                    .filter(s -> period.contains(s.date()) && belongs(s.id()) && !published.contains(s.id()))
                    .map(ShiftInfo::id)
                    .toList();
        }

        public boolean belongs(String shiftId) {
            return !shiftPeriodIds.containsKey(shiftId)||period.id().equals(shiftPeriodIds.get(shiftId));
        }
    }

    private final JdbcTemplate jdbc;
    private final ShiftRepository shiftRepository;
    private final EffectiveRulesSource rulesSource;

    public ScheduleDataRepository(JdbcTemplate jdbc, ShiftRepository shiftRepository, EffectiveRulesSource rulesSource) {
        this.jdbc = jdbc;
        this.shiftRepository = shiftRepository;
        this.rulesSource = rulesSource;
    }

    public Optional<ScheduleData> load(String periodId) {
        Optional<Period> found = jdbc.query("SELECT id, name, start_date, end_date FROM periods WHERE id = ?",
                (rs, n) -> new Period(rs.getString("id"), rs.getString("name"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                periodId).stream().findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Period period = found.get();
        ScheduleRules rules = rules();
        LocalDate from = windowStart(period, rules);
        LocalDate to = windowEnd(period, rules);

        List<ShiftInfo> shifts = new ArrayList<>();
        List<String> published = new ArrayList<>();
        Map<String,String> shiftPeriods=new HashMap<>();
        for (ShiftDetail detail : shiftRepository.search(from, to, null, false)) {
            shiftPeriods.put(detail.id(),detail.shift().periodId()==null?"":detail.shift().periodId());
            shifts.add(ShiftConfigRules.toShiftInfo(detail.id(), detail.shift()));
            if ("published".equals(detail.status()) && period.contains(detail.shift().date())&&period.id().equals(detail.shift().periodId())) {
                published.add(detail.id());
            }
        }
        List<Employee> employees = employees();
        Set<String> applicable=new HashSet<>(jdbc.queryForList("SELECT user_id FROM period_applicable_users WHERE period_id=?",String.class,periodId));
        if(!applicable.isEmpty())employees=employees.stream().filter(e->applicable.contains(e.id())).toList();
        return Optional.of(new ScheduleData(period, rules, employees, shifts, published,
                availability(periodId), assignments(from, to), blockedDates(from, to.plusDays(1)),
                rulesSource.load(period, rules, employees),shiftPeriods));
    }

    /**
     * Ngày đầu của khoảng nạp ca/phân công: lùi đến thứ hai của tuần đầu (giờ/tuần), thêm một ngày đệm (ca qua đêm,
     * nghỉ tối thiểu) và đủ số ngày liên tiếp tối đa để đếm chuỗi ngày làm bắt đầu trước kỳ.
     */
    static LocalDate windowStart(Period period, ScheduleRules rules) {
        LocalDate weekBased = period.startDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(1);
        LocalDate streakBased = period.startDate().minusDays(streakPadding(rules));
        return streakBased.isBefore(weekBased) ? streakBased : weekBased;
    }

    /** Ngày cuối của khoảng nạp, đối xứng với {@link #windowStart}. */
    static LocalDate windowEnd(Period period, ScheduleRules rules) {
        LocalDate weekBased = period.endDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).plusDays(1);
        LocalDate streakBased = period.endDate().plusDays(streakPadding(rules));
        return streakBased.isAfter(weekBased) ? streakBased : weekBased;
    }

    private static int streakPadding(ScheduleRules rules) {
        // Scoped effective values may differ from the legacy company row (maximum configurable value: 31).
        return Math.max(32, rules.maxConsecutiveDays() == null ? 0 : rules.maxConsecutiveDays() + 1);
    }

    private ScheduleRules rules() {
        record Row(String id, double day, double week, double periodHours, double rest, Double target, Integer consecutive,
                   Double standard, String priority) {
        }
        Row row = jdbc.query("""
                        SELECT TOP 1 id, max_hours_per_day, max_hours_per_week, max_hours_per_period,
                               min_rest_between_shifts, target_hours_per_week, max_consecutive_days,
                               standard_hours, scheduling_priority
                        FROM schedule_rules
                        ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, id
                        """,
                (rs, n) -> new Row(rs.getString("id"), rs.getDouble("max_hours_per_day"),
                        rs.getDouble("max_hours_per_week"), rs.getDouble("max_hours_per_period"),
                        rs.getDouble("min_rest_between_shifts"), toDouble(rs.getBigDecimal("target_hours_per_week")),
                        rs.getObject("max_consecutive_days") == null ? null : rs.getInt("max_consecutive_days"),
                        toDouble(rs.getBigDecimal("standard_hours")), rs.getString("scheduling_priority")),
                MAIN_RULES).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Chưa cấu hình quy tắc phân lịch (schedule_rules)."));

        Map<String, Set<String>> substitutions = new HashMap<>();
        jdbc.query("SELECT higher_level_id, lower_level_id FROM level_substitutions WHERE rules_id = ? AND EXISTS (SELECT 1 FROM rule_settings WHERE param_key='level_substitutions' AND status='active')",
                rs -> {
                    substitutions.computeIfAbsent(rs.getString("higher_level_id"), k -> new HashSet<>())
                            .add(rs.getString("lower_level_id"));
                }, row.id());
        Map<String, Integer> levelOrder = new HashMap<>();
        jdbc.query("SELECT id, sort_order FROM levels", rs -> {
            levelOrder.put(rs.getString("id"), rs.getInt("sort_order"));
        });
        return new ScheduleRules(row.day(), row.week(), row.periodHours(), row.rest(), row.target(), row.consecutive(),
                substitutions, levelOrder, row.standard(), row.priority());
    }

    private List<Employee> employees() {
        Map<String, Set<String>> locations = links("SELECT user_id, location_id FROM user_locations");
        Map<String, Set<String>> positions = links("SELECT user_id, position_id FROM user_positions");
        Map<String, Set<String>> skills = links("SELECT user_id, skill_id FROM user_skills");
        Map<String, Map<String, LocalDate>> certificates = new HashMap<>();
        jdbc.query("SELECT user_id, certificate_id, valid_until FROM user_certificates", rs -> {
            // valid_until NULL nghĩa là không hết hạn, nên dùng HashMap để giữ được giá trị null.
            certificates.computeIfAbsent(rs.getString("user_id"), k -> new HashMap<>())
                    .put(rs.getString("certificate_id"), rs.getObject("valid_until", LocalDate.class));
        });
        return jdbc.query("""
                        SELECT id, full_name, status, level_id, target_rate, contract_max_hours_per_week,branch_id,employee_type_id,group_id
                        FROM users
                        WHERE role = 'user'
                        ORDER BY id
                        """,
                (rs, n) -> {
                    String id = rs.getString("id");
                    return new Employee(id, rs.getString("full_name"), "active".equals(rs.getString("status")),
                            rs.getString("level_id"), toDouble(rs.getBigDecimal("target_rate")),
                            toDouble(rs.getBigDecimal("contract_max_hours_per_week")), locations.get(id), skills.get(id),
                            certificates.get(id), positions.get(id),rs.getString("branch_id"),rs.getString("employee_type_id"),rs.getString("group_id"));
                });
    }

    private Map<String, UserAvailability> availability(String periodId) {
        Map<String, Boolean> submitted = new HashMap<>();
        Map<String, Map<LocalDate, List<TimeRange>>> slots = new HashMap<>();
        Map<String, Map<LocalDate, List<TimeRange>>> blocked = new HashMap<>();
        jdbc.query("""
                SELECT r.user_id, r.period_id AS registration_period_id, r.status, e.available_date, s.start_time, s.end_time, s.slot_type
                FROM availability_registrations r
                JOIN periods context_period ON context_period.id = ?
                LEFT JOIN availability_entries e ON e.registration_id = r.id
                LEFT JOIN availability_slots s ON s.entry_id = e.id
                WHERE r.period_id = context_period.id OR (r.status='submitted'
                    AND e.available_date IN (DATEADD(day,-1,context_period.start_date),DATEADD(day,1,context_period.end_date)))
                """, rs -> {
            String userId = rs.getString("user_id");
            if(periodId.equals(rs.getString("registration_period_id")))submitted.put(userId, "submitted".equals(rs.getString("status")));
            LocalDate date = rs.getObject("available_date", LocalDate.class);
            if (date == null) {
                return;
            }
            LocalTime start = rs.getObject("start_time", LocalTime.class);
            LocalTime end = rs.getObject("end_time", LocalTime.class);
            if (start == null || end == null) return;
            TimeRange range = TimeRange.of(start, end);
            Map<String, Map<LocalDate, List<TimeRange>>> target = "unavailable".equals(rs.getString("slot_type"))
                    ? blocked : slots;
            target.computeIfAbsent(userId, k -> new HashMap<>()).computeIfAbsent(date, k -> new ArrayList<>()).add(range);
        }, periodId);
        jdbc.query("""
            SELECT l.user_id,l.start_date,l.end_date,p.start_date AS period_start,p.end_date AS period_end
            FROM leave_requests l JOIN periods p ON p.id=?
            WHERE l.status='approved' AND l.start_date<=DATEADD(day,1,p.end_date) AND l.end_date>=DATEADD(day,-1,p.start_date)
            """,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                String user=rs.getString("user_id");
                LocalDate start=rs.getObject("start_date",LocalDate.class),end=rs.getObject("end_date",LocalDate.class);
                LocalDate min=rs.getObject("period_start",LocalDate.class).minusDays(1),max=rs.getObject("period_end",LocalDate.class).plusDays(1);
                for(LocalDate date=start.isBefore(min)?min:start;!date.isAfter(end)&&!date.isAfter(max);date=date.plusDays(1))
                    blocked.computeIfAbsent(user,k->new HashMap<>()).computeIfAbsent(date,k->new ArrayList<>()).add(WHOLE_DAY);
            },periodId);
        Map<String, UserAvailability> result = new HashMap<>();
        Set<String> users=new HashSet<>(submitted.keySet());users.addAll(blocked.keySet());
        users.forEach(userId -> result.put(userId, UserAvailability.subtract(userId, submitted.getOrDefault(userId,false), slots.get(userId), blocked.get(userId))));
        return result;
    }

    private List<Assignment> assignments(LocalDate from, LocalDate to) {
        return jdbc.query("""
                        SELECT a.id, a.shift_id, a.user_id, a.status
                        FROM assignments a
                        JOIN shifts s ON s.id = a.shift_id
                        WHERE a.status = 'confirmed' AND s.cancelled_at IS NULL AND s.shift_date BETWEEN ? AND ?
                        ORDER BY s.shift_date, s.start_time, a.id
                        """,
                (rs, n) -> new Assignment(rs.getString("id"), rs.getString("shift_id"), rs.getString("user_id"),
                        rs.getString("status")),
                from, to);
    }

    private Map<String, Set<LocalDate>> blockedDates(LocalDate from, LocalDate to) {
        Map<String, Set<LocalDate>> result = new HashMap<>();
        jdbc.query("SELECT user_id, blocked_date FROM user_blocked_dates WHERE blocked_date BETWEEN ? AND ?", rs -> {
            result.computeIfAbsent(rs.getString("user_id"), k -> new HashSet<>())
                    .add(rs.getObject("blocked_date", LocalDate.class));
        }, from, to);
        return result;
    }

    private Map<String, Set<String>> links(String sql) {
        Map<String, Set<String>> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            result.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
        });
        return result;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
