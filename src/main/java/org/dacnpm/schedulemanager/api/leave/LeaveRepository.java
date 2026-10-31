package org.dacnpm.schedulemanager.api.leave;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Profile("sqlserver")
@Repository
public class LeaveRepository {

    private static final String SELECT = """
            SELECT l.*, u.full_name AS user_name, t.name AS type_name, d.full_name AS decided_name
            FROM leave_requests l
            JOIN users u ON u.id = l.user_id
            JOIN leave_types t ON t.id = l.leave_type_id
            LEFT JOIN users d ON d.id = l.decided_by
            """;

    private static final RowMapper<LeaveView> MAPPER = (rs, i) -> new LeaveView(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("user_name"),
            rs.getString("leave_type_id"),
            rs.getString("type_name"),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class),
            rs.getString("reason"),
            rs.getString("document_url"),
            rs.getString("status"),
            rs.getString("decided_by"),
            rs.getString("decided_name"),
            ts(rs.getTimestamp("decided_at")),
            rs.getString("decision_note"),
            rs.getInt("affected_assignment_count"),
            rs.getInt("version"),
            ts(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public LeaveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LeaveType> findTypes(boolean activeOnly) {
        return jdbc.query("""
                SELECT id, name, description, paid, requires_document, min_notice_days, active
                FROM leave_types WHERE (? = 0 OR active = 1) ORDER BY sort_order
                """, (rs, i) -> new LeaveType(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("paid"), rs.getBoolean("requires_document"), rs.getInt("min_notice_days"),
                rs.getBoolean("active")), activeOnly ? 1 : 0);
    }

    public Optional<LeaveType> findType(String id) {
        return findTypes(false).stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public void insert(String id, String userId, String typeId, LocalDate start, LocalDate end,
                       String reason, String documentUrl) {
        jdbc.update("""
                INSERT INTO leave_requests (id, user_id, leave_type_id, start_date, end_date, reason, document_url, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')
                """, id, userId, typeId, start, end, reason, documentUrl);
    }

    public Optional<LeaveView> findById(String id) {
        return jdbc.query(SELECT + " WHERE l.id = ?", MAPPER, id).stream().findFirst();
    }

    public List<LeaveView> find(String userId, String status, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (userId != null) {
            sql.append(" AND l.user_id = ?");
            args.add(userId);
        }
        if (status != null) {
            sql.append(" AND l.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY l.created_at DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** Có đơn pending/approved khác của cùng người chồng lên khoảng ngày? */
    public boolean overlapsActive(String userId, LocalDate start, LocalDate end) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM leave_requests
                WHERE user_id = ? AND status IN ('pending', 'approved')
                  AND start_date <= ? AND end_date >= ?
                """, Integer.class, userId, end, start);
        return n != null && n > 0;
    }

    public int decide(String id, int expectedVersion, String newStatus, String deciderId, String note) {
        return jdbc.update("""
                UPDATE leave_requests
                SET status = ?, decided_by = ?, decided_at = SYSUTCDATETIME(), decision_note = ?,
                    version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'pending'
                """, newStatus, deciderId, note, id, expectedVersion);
    }

    public void setAffectedCount(String id, int count) {
        jdbc.update("UPDATE leave_requests SET affected_assignment_count = ? WHERE id = ?", count, id);
    }

    public int cancel(String id, int expectedVersion) {
        return jdbc.update("""
                UPDATE leave_requests SET status = 'cancelled', version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'pending'
                """, id, expectedVersion);
    }

    public long countPending() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM leave_requests WHERE status = 'pending'", Long.class);
        return n == null ? 0 : n;
    }

    /* ── Vắng đột xuất ─────────────────────────────────────────────────── */

    public void insertAbsence(String id, String userId, String shiftId, String assignmentId, String reason,
                              String recordedBy) {
        jdbc.update("""
                INSERT INTO absence_records (id, user_id, shift_id, assignment_id, reason, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, userId, shiftId, assignmentId, reason, recordedBy);
    }

    public List<Map<String, Object>> findAbsences(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT a.id, a.user_id, u.full_name AS user_name, a.shift_id, s.name AS shift_name, s.shift_date,
                       a.reason, a.recorded_by, a.recorded_at
                FROM absence_records a
                JOIN users u ON u.id = a.user_id
                JOIN shifts s ON s.id = a.shift_id
                WHERE s.shift_date BETWEEN ? AND ?
                ORDER BY s.shift_date DESC
                """, from, to);
    }

    /** Số phân công confirmed trong khoảng ngày — mẫu số tỷ lệ vắng. */
    public long countConfirmedAssignments(LocalDate from, LocalDate to) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM assignments a JOIN shifts s ON s.id = a.shift_id
                WHERE a.status = 'confirmed' AND s.shift_date BETWEEN ? AND ?
                """, Long.class, from, to);
        return n == null ? 0 : n;
    }

    /** Số ngày-người nghỉ đã duyệt trong khoảng (cắt theo khoảng). */
    public long countApprovedLeaveDays(LocalDate from, LocalDate to) {
        Long n = jdbc.queryForObject("""
                SELECT COALESCE(SUM(DATEDIFF(DAY,
                         CASE WHEN start_date > ? THEN start_date ELSE ? END,
                         CASE WHEN end_date < ? THEN end_date ELSE ? END) + 1), 0)
                FROM leave_requests
                WHERE status = 'approved' AND start_date <= ? AND end_date >= ?
                """, Long.class, from, from, to, to, to, from);
        return n == null ? 0 : n;
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
