package org.dacnpm.schedulemanager.api.requests;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Profile("sqlserver")
@Repository
public class ShiftRequestRepository {

    private static final String SELECT = """
            SELECT r.*, ru.full_name AS requester_name, tu.full_name AS target_name, du.full_name AS decided_name,
                   ss.name AS s_name, ss.shift_date AS s_date, ss.start_time AS s_start, ss.end_time AS s_end,
                   ts.name AS t_name, ts.shift_date AS t_date, ts.start_time AS t_start, ts.end_time AS t_end
            FROM shift_requests r
            JOIN users ru ON ru.id = r.requester_id
            JOIN users tu ON tu.id = r.target_user_id
            LEFT JOIN users du ON du.id = r.decided_by
            JOIN shifts ss ON ss.id = r.source_shift_id
            LEFT JOIN shifts ts ON ts.id = r.target_shift_id
            """;

    private static final RowMapper<ShiftRequestView> MAPPER = ShiftRequestRepository::map;

    private final JdbcTemplate jdbc;

    public ShiftRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String type, String requesterId, String sourceAssignmentId, String sourceShiftId,
                       String targetUserId, String targetAssignmentId, String targetShiftId, String reason) {
        jdbc.update("""
                INSERT INTO shift_requests (id, request_type, requester_id, source_assignment_id, source_shift_id,
                                            target_user_id, target_assignment_id, target_shift_id, reason, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending_peer')
                """, id, type, requesterId, sourceAssignmentId, sourceShiftId,
                targetUserId, targetAssignmentId, targetShiftId, reason);
    }

    public Optional<ShiftRequestView> findById(String id) {
        return jdbc.query(SELECT + " WHERE r.id = ?", MAPPER, id).stream().findFirst();
    }

    public void captureScheduleSnapshot(String id) {
        jdbc.update("""
            UPDATE r SET source_assignment_version=a.version,source_shift_version=s.version,
                target_assignment_version=ta.version,target_shift_version=ts.version
            FROM shift_requests r JOIN assignments a ON a.id=r.source_assignment_id
            JOIN shifts s ON s.id=r.source_shift_id
            LEFT JOIN assignments ta ON ta.id=r.target_assignment_id
            LEFT JOIN shifts ts ON ts.id=r.target_shift_id WHERE r.id=?
            """,id);
    }

    public Optional<Boolean> scheduleUnchanged(String id) {
        return jdbc.query("""
            SELECT CASE WHEN r.source_assignment_version=a.version AND r.source_shift_version=s.version
                AND (r.target_assignment_id IS NULL OR (r.target_assignment_version=ta.version AND r.target_shift_version=ts.version))
                THEN 1 ELSE 0 END AS unchanged
            FROM shift_requests r JOIN assignments a ON a.id=r.source_assignment_id
            JOIN shifts s ON s.id=r.source_shift_id
            LEFT JOIN assignments ta ON ta.id=r.target_assignment_id
            LEFT JOIN shifts ts ON ts.id=r.target_shift_id WHERE r.id=?
            """,(rs,n)->rs.getInt("unchanged")==1,id).stream().findFirst();
    }

    /**
     * @param involvedUserId lọc yêu cầu mà người này là người gửi hoặc người nhận; null = tất cả (admin)
     * @param status         lọc trạng thái; null = tất cả
     */
    public List<ShiftRequestView> find(String involvedUserId, String status, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (involvedUserId != null) {
            sql.append(" AND (r.requester_id = ? OR r.target_user_id = ?)");
            args.add(involvedUserId);
            args.add(involvedUserId);
        }
        if (status != null) {
            sql.append(" AND r.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY r.created_at DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** Người nhận phản hồi: pending_peer → pending_approval | peer_declined. */
    public int peerRespond(String id, int expectedVersion, String newStatus, String note) {
        return jdbc.update("""
                UPDATE shift_requests
                SET status = ?, peer_note = ?, peer_responded_at = SYSUTCDATETIME(),
                    version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'pending_peer'
                """, newStatus, note, id, expectedVersion);
    }

    /** Quyết định của người duyệt; chỉ thành công nếu trạng thái/phiên bản chưa đổi. */
    public int decide(String id, int expectedVersion, List<String> fromStatuses, String newStatus,
                      String deciderId, String note, String finalResponsibleUserId) {
        String placeholders = String.join(",", fromStatuses.stream().map(s -> "?").toList());
        List<Object> args = new ArrayList<>(List.of(newStatus, deciderId));
        args.add(note);
        args.add(finalResponsibleUserId);
        args.add(id);
        args.add(expectedVersion);
        args.addAll(fromStatuses);
        return jdbc.update("""
                UPDATE shift_requests
                SET status = ?, decided_by = ?, decided_at = SYSUTCDATETIME(), decision_note = ?,
                    final_responsible_user_id = ?, version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status IN (""" + placeholders + ")", args.toArray());
    }

    public int cancel(String id, int expectedVersion) {
        return jdbc.update("""
                UPDATE shift_requests SET status = 'cancelled', version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status IN ('pending_peer', 'pending_approval')
                """, id, expectedVersion);
    }

    public long countByStatus(String status) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM shift_requests WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    public long countIncomingForPeer(String userId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shift_requests WHERE status = 'pending_peer' AND target_user_id = ?",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    private static ShiftRequestView map(ResultSet rs, int rowNum) throws SQLException {
        ShiftSummary source = new ShiftSummary(rs.getString("source_shift_id"), rs.getString("s_name"),
                rs.getObject("s_date", LocalDate.class), rs.getObject("s_start", LocalTime.class),
                rs.getObject("s_end", LocalTime.class));
        ShiftSummary target = rs.getString("target_shift_id") == null ? null
                : new ShiftSummary(rs.getString("target_shift_id"), rs.getString("t_name"),
                rs.getObject("t_date", LocalDate.class), rs.getObject("t_start", LocalTime.class),
                rs.getObject("t_end", LocalTime.class));
        return new ShiftRequestView(
                rs.getString("id"),
                rs.getString("request_type"),
                rs.getString("status"),
                rs.getString("requester_id"),
                rs.getString("requester_name"),
                rs.getString("source_assignment_id"),
                source,
                rs.getString("target_user_id"),
                rs.getString("target_name"),
                rs.getString("target_assignment_id"),
                target,
                rs.getString("reason"),
                rs.getString("peer_note"),
                ts(rs.getTimestamp("peer_responded_at")),
                rs.getString("decided_by"),
                rs.getString("decided_name"),
                ts(rs.getTimestamp("decided_at")),
                rs.getString("decision_note"),
                rs.getString("final_responsible_user_id"),
                rs.getInt("version"),
                ts(rs.getTimestamp("created_at")),
                ts(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
