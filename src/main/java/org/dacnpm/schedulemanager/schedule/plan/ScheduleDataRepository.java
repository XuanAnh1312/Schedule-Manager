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
     */
    public record ScheduleData(
            Period period,
            ScheduleRules rules,
            List<Employee> employees,
            List<ShiftInfo> shifts,
            List<String> publishedShiftIds,
            Map<String, UserAvailability> availability,
            List<Assignment> savedAssignments,
            Map<String, Set<LocalDate>> blockedDates) {

        public ScheduleData {
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
                    .filter(s -> period.contains(s.date()) && !published.contains(s.id()))
                    .map(ShiftInfo::id)
                    .toList();
        }
    }

    private final JdbcTemplate jdbc;
    private final ShiftRepository shiftRepository;

    public ScheduleDataRepository(JdbcTemplate jdbc, ShiftRepository shiftRepository) {
        this.jdbc = jdbc;
        this.shiftRepository = shiftRepository;
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
        for (ShiftDetail detail : shiftRepository.search(from, to, null, false)) {
            shifts.add(ShiftConfigRules.toShiftInfo(detail.id(), detail.shift()));
            if ("published".equals(detail.status()) && period.contains(detail.shift().date())) {
                published.add(detail.id());
            }
        }
        return Optional.of(new ScheduleData(period, rules, employees(), shifts, published,
                availability(periodId), assignments(from, to), blockedDates(from, to.plusDays(1))));
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
        return rules.maxConsecutiveDays() == null ? 0 : rules.maxConsecutiveDays() + 1;
    }

    private ScheduleRules rules() {
        record Row(String id, double day, double week, double periodHours, double rest, Double target, Integer consecutive) {
        }
        Row row = jdbc.query("""
                        SELECT TOP 1 id, max_hours_per_day, max_hours_per_week, max_hours_per_period,
                               min_rest_between_shifts, target_hours_per_week, max_consecutive_days
                        FROM schedule_rules
                        ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, id
                        """,
                (rs, n) -> new Row(rs.getString("id"), rs.getDouble("max_hours_per_day"),
                        rs.getDouble("max_hours_per_week"), rs.getDouble("max_hours_per_period"),
                        rs.getDouble("min_rest_between_shifts"), toDouble(rs.getBigDecimal("target_hours_per_week")),
                        rs.getObject("max_consecutive_days") == null ? null : rs.getInt("max_consecutive_days")),
                MAIN_RULES).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Chưa cấu hình quy tắc phân lịch (schedule_rules)."));

        Map<String, Set<String>> substitutions = new HashMap<>();
        jdbc.query("SELECT higher_level_id, lower_level_id FROM level_substitutions WHERE rules_id = ?",
                rs -> {
                    substitutions.computeIfAbsent(rs.getString("higher_level_id"), k -> new HashSet<>())
                            .add(rs.getString("lower_level_id"));
                }, row.id());
        Map<String, Integer> levelOrder = new HashMap<>();
        jdbc.query("SELECT id, sort_order FROM levels", rs -> {
            levelOrder.put(rs.getString("id"), rs.getInt("sort_order"));
        });
        return new ScheduleRules(row.day(), row.week(), row.periodHours(), row.rest(), row.target(), row.consecutive(),
                substitutions, levelOrder);
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
                        SELECT id, full_name, status, level_id, target_rate, contract_max_hours_per_week
                        FROM users
                        ORDER BY id
                        """,
                (rs, n) -> {
                    String id = rs.getString("id");
                    return new Employee(id, rs.getString("full_name"), "active".equals(rs.getString("status")),
                            rs.getString("level_id"), toDouble(rs.getBigDecimal("target_rate")),
                            toDouble(rs.getBigDecimal("contract_max_hours_per_week")), locations.get(id), skills.get(id),
                            certificates.get(id), positions.get(id));
                });
    }

    private Map<String, UserAvailability> availability(String periodId) {
        Map<String, Boolean> submitted = new HashMap<>();
        Map<String, Map<LocalDate, List<TimeRange>>> slots = new HashMap<>();
        jdbc.query("""
                SELECT r.user_id, r.status, e.available_date, s.start_time, s.end_time
                FROM availability_registrations r
                LEFT JOIN availability_entries e ON e.registration_id = r.id
                LEFT JOIN availability_slots s ON s.entry_id = e.id
                WHERE r.period_id = ?
                """, rs -> {
            String userId = rs.getString("user_id");
            submitted.put(userId, "submitted".equals(rs.getString("status")));
            LocalDate date = rs.getObject("available_date", LocalDate.class);
            if (date == null) {
                return;
            }
            LocalTime start = rs.getObject("start_time", LocalTime.class);
            LocalTime end = rs.getObject("end_time", LocalTime.class);
            // Ngày đăng ký rảnh không có khung giờ cụ thể được hiểu là rảnh cả ngày.
            TimeRange range = start == null || end == null ? WHOLE_DAY : TimeRange.of(start, end);
            slots.computeIfAbsent(userId, k -> new HashMap<>()).computeIfAbsent(date, k -> new ArrayList<>()).add(range);
        }, periodId);
        Map<String, UserAvailability> result = new HashMap<>();
        submitted.forEach((userId, isSubmitted) ->
                result.put(userId, new UserAvailability(userId, isSubmitted, slots.get(userId))));
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
