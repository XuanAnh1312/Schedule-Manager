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
            ts(rs.getTimestamp("created_at")),
            rs.getBoolean("half_day"),
            rs.getBigDecimal("days_requested"));

    private final JdbcTemplate jdbc;

    public LeaveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LeaveType> findTypes(boolean activeOnly) {
        return jdbc.query("""
                SELECT id, name, description, paid, requires_document, min_notice_days, active, tracks_balance
                FROM leave_types WHERE (? = 0 OR active = 1) ORDER BY sort_order
                """, (rs, i) -> new LeaveType(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("paid"), rs.getBoolean("requires_document"), rs.getInt("min_notice_days"),
                rs.getBoolean("active"), rs.getBoolean("tracks_balance")), activeOnly ? 1 : 0);
    }

    public Optional<LeaveType> findType(String id) {
        return findTypes(false).stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public void insert(String id, String userId, String typeId, LocalDate start, LocalDate end,
                       String reason, String documentUrl, boolean halfDay, java.math.BigDecimal days) {
        jdbc.update("""
                INSERT INTO leave_requests (id, user_id, leave_type_id, start_date, end_date, reason, document_url, status,
                                            half_day, days_requested)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)
                """, id, userId, typeId, start, end, reason, documentUrl, halfDay, days);
    }

    /** BAO-03: Admin hủy đơn đã duyệt (chưa tới ngày nghỉ) → cancelled. */
    public int revoke(String id, int expectedVersion, String deciderId, String note) {
        return jdbc.update("""
                UPDATE leave_requests
                SET status = 'cancelled', decided_by = ?, decided_at = SYSUTCDATETIME(), decision_note = ?,
                    version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'approved'
                """, deciderId, note, id, expectedVersion);
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
    /** BAO-04: đơn của các nhân viên mà {@code managerId} là quản lý trực tiếp. */
    public List<LeaveView> findByManager(String managerId, String status, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE u.manager_id = ?");
        List<Object> args = new ArrayList<>(List.of(managerId));
        if (status != null) {
            sql.append(" AND l.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY l.created_at DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

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

    /* ── BAO-04: báo cáo vắng mặt ─────────────────────────────────────── */

    /**
     * Mỗi nhân viên (trong phạm vi): số lần vắng đột xuất và số phân công confirmed trong khoảng ngày.
     * {@code managerId} khác null → chỉ cấp dưới trực tiếp của người đó.
     */
    public List<Map<String, Object>> absenceByEmployee(LocalDate from, LocalDate to, String managerId) {
        return jdbc.queryForList("""
                SELECT u.id AS userId, u.full_name AS fullName, u.employee_code AS employeeCode,
                       u.department_id AS departmentId, d.name AS departmentName,
                       (SELECT COUNT(*) FROM absence_records a JOIN shifts s ON s.id = a.shift_id
                         WHERE a.user_id = u.id AND s.shift_date BETWEEN ? AND ?) AS absences,
                       (SELECT COUNT(*) FROM assignments x JOIN shifts s ON s.id = x.shift_id
                         WHERE x.user_id = u.id AND x.status = 'confirmed' AND s.shift_date BETWEEN ? AND ?) AS assignments
                FROM users u LEFT JOIN departments d ON d.id = u.department_id
                WHERE u.role = 'user' AND u.archived_at IS NULL AND (? IS NULL OR u.manager_id = ?)
                ORDER BY u.full_name, u.id
                """, from, to, from, to, managerId, managerId);
    }

    /** Ngày nghỉ đã duyệt theo nhân viên × loại nghỉ, cắt theo khoảng ngày (nửa ngày = 0,5). */
    public List<Map<String, Object>> approvedLeaveDaysByType(LocalDate from, LocalDate to, String managerId) {
        return jdbc.queryForList("""
                SELECT l.user_id AS userId, l.leave_type_id AS leaveTypeId, t.name AS leaveTypeName,
                       SUM(CASE WHEN l.half_day = 1 THEN CAST(0.5 AS DECIMAL(9, 1))
                                ELSE CAST(DATEDIFF(DAY,
                                       CASE WHEN l.start_date > ? THEN l.start_date ELSE ? END,
                                       CASE WHEN l.end_date < ? THEN l.end_date ELSE ? END) + 1 AS DECIMAL(9, 1)) END) AS days
                FROM leave_requests l
                JOIN leave_types t ON t.id = l.leave_type_id
                JOIN users u ON u.id = l.user_id
                WHERE l.status = 'approved' AND l.start_date <= ? AND l.end_date >= ?
                  AND (? IS NULL OR u.manager_id = ?)
                GROUP BY l.user_id, l.leave_type_id, t.name
                """, from, from, to, to, to, from, managerId, managerId);
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
