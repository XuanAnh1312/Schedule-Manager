package org.dacnpm.schedulemanager.api.performance;

import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.dacnpm.schedulemanager.api.performance.PerformanceModels.*;

/** Truy cập dữ liệu KPI / kỳ đánh giá / phiếu / thành tích (BAO-06). */
@Profile("sqlserver")
@Repository
public class PerformanceRepository {

    private static final RowMapper<Kpi> KPI = (rs, i) -> new Kpi(rs.getString("id"), rs.getString("name"),
            rs.getString("description"), rs.getString("unit"), rs.getString("formula_type"), rs.getString("direction"),
            rs.getBigDecimal("target_value"), rs.getBigDecimal("weight"), rs.getString("department_id"),
            rs.getBoolean("active"), rs.getInt("version"));

    private static final RowMapper<Cycle> CYCLE = (rs, i) -> new Cycle(rs.getString("id"), rs.getString("name"),
            rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
            rs.getInt("score_scale_max"), rs.getString("status"), rs.getInt("version"), rs.getLong("review_count"),
            rs.getLong("completed_count"));

    private static final String REVIEW_SELECT = """
            SELECT r.*, c.name AS cycle_name, c.period_start, c.period_end, c.score_scale_max,
                   e.full_name AS employee_name, v.full_name AS reviewer_name
            FROM reviews r
            JOIN review_cycles c ON c.id = r.cycle_id
            JOIN users e ON e.id = r.employee_id
            LEFT JOIN users v ON v.id = r.reviewer_id
            """;

    private static final RowMapper<Review> REVIEW = (rs, i) -> new Review(rs.getString("id"), rs.getString("cycle_id"),
            rs.getString("cycle_name"), rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class), rs.getInt("score_scale_max"), rs.getString("employee_id"),
            rs.getString("employee_name"), rs.getString("reviewer_id"), rs.getString("reviewer_name"),
            rs.getString("status"), rs.getString("self_comment"), rs.getString("manager_comment"),
            rs.getString("improvement_plan"), rs.getString("employee_feedback"), rs.getBigDecimal("overall_score"),
            ts(rs.getTimestamp("self_submitted_at")), ts(rs.getTimestamp("manager_submitted_at")),
            ts(rs.getTimestamp("acknowledged_at")), rs.getInt("version"), List.of());

    private static final RowMapper<Item> ITEM = (rs, i) -> new Item(rs.getString("id"), rs.getString("review_id"),
            rs.getString("kpi_id"), rs.getString("kpi_name"), rs.getString("unit"), rs.getString("formula_type"),
            rs.getString("direction"), rs.getBigDecimal("target_value"), rs.getBigDecimal("weight"),
            rs.getBigDecimal("actual_value"), rs.getString("actual_basis"), rs.getBigDecimal("self_score"),
            rs.getBigDecimal("manager_score"), rs.getString("comment"));

    private final JdbcTemplate jdbc;

    public PerformanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ── KPI ────────────────────────────────────────────────────────────── */

    public List<Kpi> kpis(boolean activeOnly) {
        return jdbc.query("SELECT * FROM kpi_definitions WHERE (? = 0 OR active = 1) ORDER BY name", KPI, activeOnly ? 1 : 0);
    }

    public Optional<Kpi> kpi(String id) {
        return jdbc.query("SELECT * FROM kpi_definitions WHERE id = ?", KPI, id).stream().findFirst();
    }

    public void insertKpi(String id, KpiInput in, String departmentId, String actorId) {
        jdbc.update("""
                INSERT INTO kpi_definitions (id, name, description, unit, formula_type, direction, target_value, weight,
                                             department_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, in.name().trim(), in.description(), in.unit().trim(), in.formulaType(), in.direction(),
                in.targetValue(), in.weight(), departmentId, actorId);
    }

    public int updateKpi(String id, int expectedVersion, KpiInput in, String departmentId, boolean active) {
        return jdbc.update("""
                UPDATE kpi_definitions SET name = ?, description = ?, unit = ?, formula_type = ?, direction = ?,
                       target_value = ?, weight = ?, department_id = ?, active = ?, version = version + 1
                WHERE id = ? AND version = ?
                """, in.name().trim(), in.description(), in.unit().trim(), in.formulaType(), in.direction(),
                in.targetValue(), in.weight(), departmentId, active, id, expectedVersion);
    }

    public boolean departmentExists(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM departments WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /* ── Kỳ đánh giá ────────────────────────────────────────────────────── */

    private static final String CYCLE_SELECT = """
            SELECT c.*, (SELECT COUNT(*) FROM reviews r WHERE r.cycle_id = c.id AND r.status <> 'cancelled') AS review_count,
                   (SELECT COUNT(*) FROM reviews r WHERE r.cycle_id = c.id AND r.status = 'completed') AS completed_count
            FROM review_cycles c
            """;

    public List<Cycle> cycles() {
        return jdbc.query(CYCLE_SELECT + " ORDER BY c.period_start DESC", CYCLE);
    }

    public Optional<Cycle> cycle(String id) {
        return jdbc.query(CYCLE_SELECT + " WHERE c.id = ?", CYCLE, id).stream().findFirst();
    }

    public void insertCycle(String id, String name, LocalDate start, LocalDate end, int scale, String actorId) {
        jdbc.update("""
                INSERT INTO review_cycles (id, name, period_start, period_end, score_scale_max, status, created_by)
                VALUES (?, ?, ?, ?, ?, 'draft', ?)
                """, id, name, start, end, scale, actorId);
    }

    public int setCycleStatus(String id, int expectedVersion, String from, String to) {
        return jdbc.update("""
                UPDATE review_cycles SET status = ?, version = version + 1 WHERE id = ? AND version = ? AND status = ?
                """, to, id, expectedVersion, from);
    }

    /** Nhân viên đang hoạt động và quản lý trực tiếp (đang hoạt động) của họ, kèm bộ phận. */
    public List<Map<String, Object>> activeEmployees() {
        return jdbc.queryForList("""
                SELECT u.id, u.department_id AS departmentId,
                       CASE WHEN m.status = 'active' AND m.archived_at IS NULL AND m.id <> u.id THEN m.id END AS managerId
                FROM users u LEFT JOIN users m ON m.id = u.manager_id
                WHERE u.role = 'user' AND u.status = 'active' AND u.archived_at IS NULL
                ORDER BY u.id
                """);
    }

    /** Số liệu tự tính KPI của một người trong khoảng ngày (ca theo ngày bắt đầu ca). */
    public Metrics metrics(String userId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM assignments a JOIN shifts s ON s.id = a.shift_id
                    WHERE a.user_id = ? AND a.status = 'confirmed' AND s.shift_date BETWEEN ? AND ?) AS assignments,
                  (SELECT COALESCE(SUM((DATEDIFF(MINUTE, s.start_time, s.end_time) + 1440) % 1440 - s.break_minutes), 0)
                     FROM assignments a JOIN shifts s ON s.id = a.shift_id
                    WHERE a.user_id = ? AND a.status = 'confirmed' AND s.shift_date BETWEEN ? AND ?) AS scheduled_minutes,
                  (SELECT COUNT(*) FROM attendance_records r JOIN shifts s ON s.id = r.shift_id
                    WHERE r.user_id = ? AND s.shift_date BETWEEN ? AND ?) AS attended,
                  (SELECT COALESCE(SUM(DATEDIFF(MINUTE, r.clock_in_at, r.clock_out_at) - r.break_minutes), 0)
                     FROM attendance_records r JOIN shifts s ON s.id = r.shift_id
                    WHERE r.user_id = ? AND r.clock_out_at IS NOT NULL AND s.shift_date BETWEEN ? AND ?) AS worked_minutes,
                  (SELECT COUNT(*) FROM absence_records b JOIN shifts s ON s.id = b.shift_id
                    WHERE b.user_id = ? AND s.shift_date BETWEEN ? AND ?) AS absences
                """, (rs, i) -> new Metrics(rs.getLong("assignments"), rs.getLong("scheduled_minutes"),
                rs.getLong("attended"), rs.getLong("worked_minutes"), rs.getLong("absences")),
                userId, from, to, userId, from, to, userId, from, to, userId, from, to, userId, from, to);
    }

    /* ── Phiếu đánh giá ─────────────────────────────────────────────────── */

    public void insertReview(String id, String cycleId, String employeeId, String reviewerId) {
        jdbc.update("""
                INSERT INTO reviews (id, cycle_id, employee_id, reviewer_id, status) VALUES (?, ?, ?, ?, 'pending_self')
                """, id, cycleId, employeeId, reviewerId);
    }

    public void insertItem(String reviewId, Kpi k, Computed computed) {
        jdbc.update("""
                INSERT INTO review_items (id, review_id, kpi_id, kpi_name, unit, formula_type, direction, target_value,
                                          weight, actual_value, actual_basis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.next("rvi"), reviewId, k.id(), k.name(), k.unit(), k.formulaType(), k.direction(),
                k.targetValue(), k.weight(), computed.value(), computed.basis());
    }

    public Optional<Review> review(String id) {
        return jdbc.query(REVIEW_SELECT + " WHERE r.id = ?", REVIEW, id).stream().findFirst()
                .map(r -> r.withItems(items(id)));
    }

    public List<Item> items(String reviewId) {
        return jdbc.query("SELECT * FROM review_items WHERE review_id = ? ORDER BY kpi_name", ITEM, reviewId);
    }

    /** employeeId / reviewerId / cycleId: lọc nếu khác null. */
    public List<Review> reviews(String employeeId, String reviewerId, String cycleId, boolean adminReviewerOnly) {
        StringBuilder sql = new StringBuilder(REVIEW_SELECT).append(" WHERE r.status <> 'cancelled'");
        List<Object> args = new ArrayList<>();
        if (employeeId != null) { sql.append(" AND r.employee_id = ?"); args.add(employeeId); }
        if (reviewerId != null) { sql.append(" AND r.reviewer_id = ?"); args.add(reviewerId); }
        if (adminReviewerOnly) sql.append(" AND r.reviewer_id IS NULL");
        if (cycleId != null) { sql.append(" AND r.cycle_id = ?"); args.add(cycleId); }
        sql.append(" ORDER BY c.period_start DESC, e.full_name");
        return jdbc.query(sql.toString(), REVIEW, args.toArray());
    }

    public void scoreItem(String itemId, String reviewId, BigDecimal selfScore, BigDecimal managerScore,
                          BigDecimal actual, boolean setActual, String comment) {
        jdbc.update("""
                UPDATE review_items
                SET self_score = COALESCE(?, self_score), manager_score = COALESCE(?, manager_score),
                    actual_value = CASE WHEN ? = 1 THEN ? ELSE actual_value END,
                    comment = COALESCE(?, comment)
                WHERE id = ? AND review_id = ?
                """, selfScore, managerScore, setActual ? 1 : 0, actual, comment, itemId, reviewId);
    }

    public int submitSelf(String id, int expectedVersion, String comment) {
        return jdbc.update("""
                UPDATE reviews SET status = 'pending_manager', self_comment = ?, self_submitted_at = SYSUTCDATETIME(),
                       version = version + 1
                WHERE id = ? AND version = ? AND status = 'pending_self'
                """, comment, id, expectedVersion);
    }

    public int submitManager(String id, int expectedVersion, String comment, String plan, BigDecimal overall,
                             String actorId) {
        return jdbc.update("""
                UPDATE reviews SET status = 'pending_ack', manager_comment = ?, improvement_plan = ?, overall_score = ?,
                       manager_submitted_at = SYSUTCDATETIME(), manager_submitted_by = ?, version = version + 1
                WHERE id = ? AND version = ? AND status = 'pending_manager'
                """, comment, plan, overall, actorId, id, expectedVersion);
    }

    public int acknowledge(String id, int expectedVersion, String feedback) {
        return jdbc.update("""
                UPDATE reviews SET status = 'completed', employee_feedback = ?, acknowledged_at = SYSUTCDATETIME(),
                       version = version + 1
                WHERE id = ? AND version = ? AND status = 'pending_ack'
                """, feedback, id, expectedVersion);
    }

    public int cancelOpenReviews(String cycleId) {
        return jdbc.update("UPDATE reviews SET status = 'cancelled', version = version + 1 WHERE cycle_id = ? AND status <> 'completed'",
                cycleId);
    }

    public long countUnfinished(String cycleId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reviews WHERE cycle_id = ? AND status NOT IN ('completed', 'cancelled')
                """, Long.class, cycleId);
        return n == null ? 0 : n;
    }

    /* ── Thành tích ─────────────────────────────────────────────────────── */

    public void insertAchievement(String id, AchievementInput in, String actorId) {
        jdbc.update("""
                INSERT INTO achievements (id, employee_id, kind, title, description, suggested_amount, review_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, in.employeeId(), in.kind(), in.title().trim(), in.description(), in.suggestedAmount(),
                in.reviewId(), actorId);
    }

    public List<Achievement> achievements(String employeeId) {
        return jdbc.query("""
                SELECT a.*, e.full_name AS employee_name, c.full_name AS created_by_name FROM achievements a
                JOIN users e ON e.id = a.employee_id JOIN users c ON c.id = a.created_by
                WHERE (? IS NULL OR a.employee_id = ?) ORDER BY a.created_at DESC
                """, (rs, i) -> new Achievement(rs.getString("id"), rs.getString("employee_id"),
                rs.getString("employee_name"), rs.getString("kind"), rs.getString("title"), rs.getString("description"),
                rs.getBigDecimal("suggested_amount"), rs.getString("review_id"), rs.getString("created_by"),
                rs.getString("created_by_name"), ts(rs.getTimestamp("created_at"))), employeeId, employeeId);
    }

    public boolean isManagerOf(String managerId, String userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ? AND manager_id = ?", Integer.class,
                userId, managerId);
        return n != null && n > 0;
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
