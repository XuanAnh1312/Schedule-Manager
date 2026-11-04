package org.dacnpm.schedulemanager.domain.schedule;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lớp nền KHÔNG phải bean (BA-R1): chỉ còn được dùng làm phần đọc dữ liệu (find*) cho
 * {@link OfficialJdbcScheduleAdapter}, bean duy nhất của hai hợp đồng lịch. Mọi kiểm tra và ghi lịch đi qua lõi
 * lịch chính thức ({@code OfficialScheduleService}); các phương thức kiểm tra/ghi bên dưới là bản tạm cũ, được lớp con
 * ghi đè và không được nạp như một bean ghi lịch song song. Không thêm lại {@code @Component} cho lớp này.
 *
 * <p>Ràng buộc đã kiểm tra: tài khoản hoạt động, đã có trong ca, trùng giờ (kể cả ca qua đêm),
 * nghỉ tối thiểu giữa 2 ca, giờ tối đa/ngày, giờ tối đa/tuần (T2–CN), giờ tối đa/kỳ (theo
 * {@code schedule_rules}), Level (bằng hoặc được phép thay thế).
 * <br>Chưa có: trần giờ theo mục tiêu × % (AC-01, Tường/Tuấn), kỹ năng/chứng chỉ (mốc B).
 */
@Profile("sqlserver")
public class InterimJdbcScheduleAdapter implements AssignmentValidationService, AssignmentMutationService {

    private static final RowMapper<AssignmentInfo> ASSIGNMENT_MAPPER = (rs, i) -> new AssignmentInfo(
            rs.getString("assignment_id"),
            rs.getString("shift_id"),
            rs.getString("user_id"),
            rs.getString("assignment_status"),
            rs.getString("shift_name"),
            rs.getObject("shift_date", LocalDate.class),
            rs.getObject("start_time", LocalTime.class),
            rs.getObject("end_time", LocalTime.class),
            rs.getString("shift_status"));

    private static final String ASSIGNMENT_SELECT = """
            SELECT a.id AS assignment_id, a.shift_id, a.user_id, a.status AS assignment_status,
                   s.name AS shift_name, s.shift_date, s.start_time, s.end_time, s.status AS shift_status
            FROM assignments a JOIN shifts s ON s.id = a.shift_id
            """;

    private final JdbcTemplate jdbc;

    public InterimJdbcScheduleAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ── Đọc ─────────────────────────────────────────────────────────────── */

    @Override
    public Optional<AssignmentInfo> findAssignment(String assignmentId) {
        return jdbc.query(ASSIGNMENT_SELECT + " WHERE a.id = ?", ASSIGNMENT_MAPPER, assignmentId)
                .stream().findFirst();
    }

    @Override
    public Optional<ShiftInfo> findShift(String shiftId) {
        return jdbc.query("""
                SELECT s.id, s.name, s.shift_date, s.start_time, s.end_time, s.status, s.total_required,
                       (SELECT COUNT(*) FROM assignments a WHERE a.shift_id = s.id AND a.status = 'confirmed') AS confirmed
                FROM shifts s WHERE s.id = ?
                """, (rs, i) -> new ShiftInfo(
                rs.getString("id"),
                rs.getString("name"),
                rs.getObject("shift_date", LocalDate.class),
                rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class),
                rs.getString("status"),
                rs.getInt("total_required"),
                rs.getInt("confirmed")), shiftId).stream().findFirst();
    }

    @Override
    public List<AssignmentInfo> findConfirmedAssignments(String userId, LocalDate from, LocalDate to) {
        return jdbc.query(ASSIGNMENT_SELECT + """
                 WHERE a.user_id = ? AND a.status = 'confirmed' AND s.shift_date BETWEEN ? AND ?
                 ORDER BY s.shift_date, s.start_time
                """, ASSIGNMENT_MAPPER, userId, from, to);
    }

    /* ── Kiểm tra ────────────────────────────────────────────────────────── */

    @Override
    public List<Violation> validate(String userId, String shiftId, String replacingUserId,
                                    Set<String> ignoreAssignmentIds, String requiredLevelId) {
        List<Violation> violations = new ArrayList<>();
        Set<String> ignore = ignoreAssignmentIds == null ? Set.of() : ignoreAssignmentIds;

        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT role, status, level_id FROM users WHERE id = ?", userId);
        if (users.isEmpty()) {
            violations.add(Violation.block("USER_NOT_FOUND", "Không tìm thấy nhân viên."));
            return violations;
        }
        Map<String, Object> user = users.get(0);
        if (!"active".equals(user.get("status")) || !"user".equals(user.get("role"))) {
            violations.add(Violation.block("USER_INACTIVE", "Nhân viên không ở trạng thái hoạt động."));
        }

        ShiftInfo shift = findShift(shiftId).orElse(null);
        if (shift == null) {
            violations.add(Violation.block("SHIFT_NOT_FOUND", "Không tìm thấy ca."));
            return violations;
        }

        // Lấy phân công trong ±8 ngày (đủ cho tuần + ca qua đêm) trừ các phân công sẽ được gỡ.
        List<ShiftInfo> others = new ArrayList<>();
        for (AssignmentInfo a : findConfirmedAssignments(userId, shift.date().minusDays(8), shift.date().plusDays(8))) {
            if (ignore.contains(a.assignmentId())) continue;
            if (a.shiftId().equals(shiftId)) {
                violations.add(Violation.block("ALREADY_ASSIGNED", "Nhân viên đã có trong ca này."));
                continue;
            }
            others.add(new ShiftInfo(a.shiftId(), a.shiftName(), a.shiftDate(), a.startTime(), a.endTime(),
                    a.shiftStatus(), 0, 0));
        }

        Map<String, Object> rules = loadRules();
        double minRestHours = number(rules, "min_rest_between_shifts");
        double maxDay = number(rules, "max_hours_per_day");
        double maxWeek = number(rules, "max_hours_per_week");
        double maxPeriod = number(rules, "max_hours_per_period");

        double dayHours = 0;
        double weekHours = 0;
        LocalDate weekStart = shift.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        for (ShiftInfo o : others) {
            if (overlaps(shift, o)) {
                violations.add(Violation.block("OVERLAP", "Trùng lịch với " + o.label() + "."));
            } else if (minRestHours > 0) {
                double gap = gapHours(shift, o);
                if (gap < minRestHours) {
                    violations.add(Violation.block("MIN_REST",
                            "Không đủ " + fmt(minRestHours) + " giờ nghỉ so với " + o.label() + "."));
                }
            }
            if (o.date().equals(shift.date())) dayHours += o.hours();
            if (!o.date().isBefore(weekStart) && !o.date().isAfter(weekEnd)) weekHours += o.hours();
        }
        if (maxDay > 0 && dayHours + shift.hours() > maxDay) {
            violations.add(Violation.block("MAX_HOURS_DAY",
                    "Vượt " + fmt(maxDay) + " giờ/ngày (đã có " + fmt(dayHours) + " giờ)."));
        }
        if (maxWeek > 0 && weekHours + shift.hours() > maxWeek) {
            violations.add(Violation.block("MAX_HOURS_WEEK",
                    "Vượt " + fmt(maxWeek) + " giờ/tuần (đã có " + fmt(weekHours) + " giờ)."));
        }
        if (maxPeriod > 0) {
            double periodHours = periodHours(userId, shift.date(), ignore);
            if (periodHours >= 0 && periodHours + shift.hours() > maxPeriod) {
                violations.add(Violation.block("MAX_HOURS_PERIOD",
                        "Vượt " + fmt(maxPeriod) + " giờ/kỳ (đã có " + fmt(periodHours) + " giờ)."));
            }
        }

        String userLevel = (String) user.get("level_id");
        String neededLevel = requiredLevelId;
        if (neededLevel == null && replacingUserId != null) {
            neededLevel = jdbc.queryForList("SELECT level_id FROM users WHERE id = ?", String.class, replacingUserId)
                    .stream().findFirst().orElse(null);
        }
        if (neededLevel != null && !levelAllowed(userLevel, neededLevel)) {
            violations.add(Violation.block("LEVEL_NOT_ELIGIBLE",
                    "Level của nhân viên không đủ điều kiện thay vị trí Level " + neededLevel + "."));
        }
        return violations;
    }

    private boolean levelAllowed(String userLevel, String neededLevel) {
        if (neededLevel.equals(userLevel)) return true;
        if (userLevel == null) return false;
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM level_substitutions
                WHERE higher_level_id = ? AND lower_level_id = ?
                """, Integer.class, userLevel, neededLevel);
        return n != null && n > 0;
    }

    /** Tổng giờ đã phân trong kỳ chứa ngày ca; -1 nếu ngày không thuộc kỳ nào. */
    private double periodHours(String userId, LocalDate date, Set<String> ignore) {
        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT TOP 1 start_date, end_date FROM periods WHERE ? BETWEEN start_date AND end_date ORDER BY start_date DESC",
                date);
        if (periods.isEmpty()) return -1;
        LocalDate from = toLocalDate(periods.get(0).get("start_date"));
        LocalDate to = toLocalDate(periods.get(0).get("end_date"));
        double total = 0;
        for (AssignmentInfo a : findConfirmedAssignments(userId, from, to)) {
            if (ignore.contains(a.assignmentId())) continue;
            total += new ShiftInfo(a.shiftId(), a.shiftName(), a.shiftDate(), a.startTime(), a.endTime(),
                    a.shiftStatus(), 0, 0).hours();
        }
        return total;
    }

    private Map<String, Object> loadRules() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT TOP 1 min_rest_between_shifts, max_hours_per_day, max_hours_per_week, max_hours_per_period
                FROM schedule_rules ORDER BY updated_at DESC
                """);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    static boolean overlaps(ShiftInfo a, ShiftInfo b) {
        return a.startsAt().isBefore(b.endsAt()) && b.startsAt().isBefore(a.endsAt());
    }

    /** Khoảng nghỉ giữa hai ca không chồng nhau (giờ). */
    static double gapHours(ShiftInfo a, ShiftInfo b) {
        LocalDateTime gapStart;
        LocalDateTime gapEnd;
        if (!a.startsAt().isBefore(b.endsAt())) {
            gapStart = b.endsAt();
            gapEnd = a.startsAt();
        } else {
            gapStart = a.endsAt();
            gapEnd = b.startsAt();
        }
        return Duration.between(gapStart, gapEnd).toMinutes() / 60.0;
    }

    private static double number(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof BigDecimal bd) return bd.doubleValue();
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private static String fmt(double hours) {
        return hours == Math.floor(hours) ? String.valueOf((long) hours) : String.format("%.1f", hours);
    }

    /* ── Sửa lịch ────────────────────────────────────────────────────────── */

    @Override
    public void reassign(String assignmentId, String expectedUserId, String newUserId, String actorId, String reason) {
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE assignments
                    SET user_id = ?, assigned_by = ?, assigned_at = SYSUTCDATETIME(), reason = ?
                    WHERE id = ? AND user_id = ? AND status = 'confirmed'
                    """, newUserId, actorId, reason, assignmentId, expectedUserId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SCHEDULE_CHANGED", "Người nhận đã có trong ca này.");
        }
        if (updated != 1) {
            throw ApiException.conflict("SCHEDULE_CHANGED",
                    "Lịch đã thay đổi kể từ khi tạo yêu cầu (phân công không còn thuộc người gửi).");
        }
        touchShiftOf(assignmentId);
    }

    @Override
    public String addAssignment(String shiftId, String userId, String actorId, String reason) {
        // Nếu từng có phân công đã hủy cho (ca, người) thì kích hoạt lại — bảng có UNIQUE(shift_id, user_id).
        List<String> existing = jdbc.queryForList(
                "SELECT id FROM assignments WHERE shift_id = ? AND user_id = ? AND status = 'cancelled'",
                String.class, shiftId, userId);
        String id;
        if (!existing.isEmpty()) {
            id = existing.get(0);
            jdbc.update("""
                    UPDATE assignments SET status = 'confirmed', assigned_by = ?, assigned_at = SYSUTCDATETIME(), reason = ?
                    WHERE id = ? AND status = 'cancelled'
                    """, actorId, reason, id);
        } else {
            id = Ids.next("asgn");
            try {
                jdbc.update("""
                        INSERT INTO assignments (id, shift_id, user_id, assigned_by, reason, status)
                        VALUES (?, ?, ?, ?, ?, 'confirmed')
                        """, id, shiftId, userId, actorId, reason);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ALREADY_ASSIGNED", "Nhân viên đã có trong ca này.");
            }
        }
        jdbc.update("UPDATE shifts SET updated_at = SYSUTCDATETIME() WHERE id = ?", shiftId);
        return id;
    }

    @Override
    public void cancelAssignment(String assignmentId, String expectedUserId, String actorId, String reason) {
        int updated = jdbc.update("""
                UPDATE assignments SET status = 'cancelled', assigned_by = ?, assigned_at = SYSUTCDATETIME(), reason = ?
                WHERE id = ? AND user_id = ? AND status = 'confirmed'
                """, actorId, reason, assignmentId, expectedUserId);
        if (updated != 1) {
            throw ApiException.conflict("SCHEDULE_CHANGED", "Phân công đã thay đổi, vui lòng tải lại.");
        }
        touchShiftOf(assignmentId);
    }

    /** Bản tạm: gỡ hai phân công cũ rồi thêm hai phân công mới trong giao dịch của bên gọi. */
    @Override
    public void swap(String firstAssignmentId, String firstUserId, String secondAssignmentId, String secondUserId,
                     String actorId, String reason) {
        AssignmentInfo first = findAssignment(firstAssignmentId)
                .orElseThrow(() -> ApiException.conflict("SCHEDULE_CHANGED", "Phân công đã thay đổi, vui lòng tải lại."));
        AssignmentInfo second = findAssignment(secondAssignmentId)
                .orElseThrow(() -> ApiException.conflict("SCHEDULE_CHANGED", "Phân công đã thay đổi, vui lòng tải lại."));
        cancelAssignment(firstAssignmentId, firstUserId, actorId, reason);
        cancelAssignment(secondAssignmentId, secondUserId, actorId, reason);
        addAssignment(first.shiftId(), secondUserId, actorId, reason);
        addAssignment(second.shiftId(), firstUserId, actorId, reason);
    }

    private void touchShiftOf(String assignmentId) {
        jdbc.update("""
                UPDATE shifts SET updated_at = SYSUTCDATETIME()
                WHERE id = (SELECT shift_id FROM assignments WHERE id = ?)
                """, assignmentId);
    }
}
