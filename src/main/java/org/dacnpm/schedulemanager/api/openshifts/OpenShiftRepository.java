package org.dacnpm.schedulemanager.api.openshifts;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Profile("sqlserver")
@Repository
public class OpenShiftRepository {

    private static final String SELECT = """
            SELECT o.*, s.name AS shift_name, s.shift_date, s.start_time, s.end_time,
                   (SELECT COUNT(*) FROM open_shift_claims c WHERE c.open_shift_id = o.id AND c.status = 'pending') AS pending_claims,
                   mc.id AS my_claim_id, mc.status AS my_claim_status
            FROM open_shifts o
            JOIN shifts s ON s.id = o.shift_id
            LEFT JOIN open_shift_claims mc ON mc.open_shift_id = o.id AND mc.user_id = ?
            """;

    private static final RowMapper<OpenShiftView> MAPPER = (rs, i) -> new OpenShiftView(
            rs.getString("id"),
            rs.getString("shift_id"),
            rs.getString("shift_name"),
            rs.getObject("shift_date", LocalDate.class),
            rs.getObject("start_time", LocalTime.class),
            rs.getObject("end_time", LocalTime.class),
            rs.getInt("slots"),
            rs.getInt("filled_count"),
            rs.getString("required_level_id"),
            rs.getObject("claim_deadline", LocalDateTime.class),
            rs.getString("note"),
            rs.getString("status"),
            rs.getInt("pending_claims"),
            rs.getString("my_claim_id"),
            rs.getString("my_claim_status"),
            rs.getInt("version"),
            ts(rs.getTimestamp("created_at")));

    private static final RowMapper<ClaimView> CLAIM_MAPPER = (rs, i) -> new ClaimView(
            rs.getString("id"),
            rs.getString("open_shift_id"),
            rs.getString("user_id"),
            rs.getString("user_name"),
            rs.getString("status"),
            rs.getString("note"),
            rs.getString("decided_by"),
            ts(rs.getTimestamp("decided_at")),
            rs.getString("decision_note"),
            rs.getString("assignment_id"),
            rs.getInt("version"),
            ts(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public OpenShiftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String shiftId, int slots, String levelId, LocalDateTime deadlineUtc,
                       String note, String createdBy) {
        jdbc.update("""
                INSERT INTO open_shifts (id, shift_id, slots, required_level_id, claim_deadline, note, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'open', ?)
                """, id, shiftId, slots, levelId, deadlineUtc, note, createdBy);
    }

    public boolean hasOpenForShift(String shiftId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM open_shifts WHERE shift_id = ? AND status = 'open'", Integer.class, shiftId);
        return n != null && n > 0;
    }

    public Optional<OpenShiftView> findById(String id, String viewerId) {
        return jdbc.query(SELECT + " WHERE o.id = ?", MAPPER, viewerId, id).stream().findFirst();
    }

    /** Admin: mọi ca trống (lọc trạng thái). */
    public List<OpenShiftView> findAll(String status, String viewerId) {
        if (status == null) {
            return jdbc.query(SELECT + " ORDER BY s.shift_date DESC, s.start_time", MAPPER, viewerId);
        }
        return jdbc.query(SELECT + " WHERE o.status = ? ORDER BY s.shift_date, s.start_time", MAPPER, viewerId, status);
    }

    /** Nhân viên: ca đang mở còn hạn, hoặc ca mình đã đăng ký. */
    public List<OpenShiftView> findVisibleTo(String userId) {
        return jdbc.query(SELECT + """
                 WHERE (o.status = 'open' AND o.claim_deadline > SYSUTCDATETIME()) OR mc.id IS NOT NULL
                 ORDER BY s.shift_date, s.start_time
                """, MAPPER, userId);
    }

    /**
     * Giữ một chỗ: tăng filled_count nếu còn chỗ, ca còn mở. Một câu UPDATE nguyên tử nên hai lượt
     * duyệt đồng thời không thể cùng chiếm chỗ cuối (CHECK filled_count ≤ slots là lớp chặn cuối).
     */
    public int reserveSlot(String id) {
        return jdbc.update("""
                UPDATE open_shifts
                SET filled_count = filled_count + 1,
                    status = CASE WHEN filled_count + 1 >= slots THEN 'filled' ELSE status END,
                    version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND status = 'open' AND filled_count < slots
                """, id);
    }

    public int close(String id, int expectedVersion, String newStatus) {
        return jdbc.update("""
                UPDATE open_shifts SET status = ?, version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'open'
                """, newStatus, id, expectedVersion);
    }

    /* ── Claims ─────────────────────────────────────────────────────────── */

    private static final String CLAIM_SELECT = """
            SELECT c.*, u.full_name AS user_name FROM open_shift_claims c JOIN users u ON u.id = c.user_id
            """;

    public Optional<ClaimView> findClaim(String claimId) {
        return jdbc.query(CLAIM_SELECT + " WHERE c.id = ?", CLAIM_MAPPER, claimId).stream().findFirst();
    }

    public Optional<ClaimView> findClaimOf(String openShiftId, String userId) {
        return jdbc.query(CLAIM_SELECT + " WHERE c.open_shift_id = ? AND c.user_id = ?", CLAIM_MAPPER,
                openShiftId, userId).stream().findFirst();
    }

    public List<ClaimView> findClaims(String openShiftId) {
        return jdbc.query(CLAIM_SELECT + " WHERE c.open_shift_id = ? ORDER BY c.created_at", CLAIM_MAPPER, openShiftId);
    }

    public void insertClaim(String id, String openShiftId, String userId, String note) {
        jdbc.update("""
                INSERT INTO open_shift_claims (id, open_shift_id, user_id, status, note)
                VALUES (?, ?, ?, 'pending', ?)
                """, id, openShiftId, userId, note);
    }

    /** Đăng ký lại sau khi đã rút (UNIQUE open_shift_id + user_id). */
    public int reopenWithdrawnClaim(String claimId, String note) {
        return jdbc.update("""
                UPDATE open_shift_claims SET status = 'pending', note = ?, version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND status = 'withdrawn'
                """, note, claimId);
    }

    /** Quyết định của Admin: pending → approved | rejected (chỉ khi version chưa đổi). */
    public int decideClaim(String claimId, int expectedVersion, String toStatus, String deciderId, String note) {
        return jdbc.update("""
                UPDATE open_shift_claims
                SET status = ?, decided_by = ?, decided_at = SYSUTCDATETIME(), decision_note = ?,
                    version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'pending'
                """, toStatus, deciderId, note, claimId, expectedVersion);
    }

    /** Nhân viên rút đăng ký khi còn chờ. */
    public int withdrawClaim(String claimId, int expectedVersion) {
        return jdbc.update("""
                UPDATE open_shift_claims SET status = 'withdrawn', version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'pending'
                """, claimId, expectedVersion);
    }

    public void setClaimAssignment(String claimId, String assignmentId) {
        jdbc.update("UPDATE open_shift_claims SET assignment_id = ? WHERE id = ?", assignmentId, claimId);
    }

    /** Từ chối mọi đăng ký còn chờ (khi ca đủ người hoặc bị đóng); trả về userId bị ảnh hưởng. */
    public List<String> rejectPendingClaims(String openShiftId, String deciderId, String note) {
        List<String> users = jdbc.queryForList(
                "SELECT user_id FROM open_shift_claims WHERE open_shift_id = ? AND status = 'pending'",
                String.class, openShiftId);
        jdbc.update("""
                UPDATE open_shift_claims
                SET status = 'rejected', decided_by = ?, decided_at = SYSUTCDATETIME(), decision_note = ?,
                    version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE open_shift_id = ? AND status = 'pending'
                """, deciderId, note, openShiftId);
        return users;
    }

    public long countPendingClaims() {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM open_shift_claims c JOIN open_shifts o ON o.id = c.open_shift_id
                WHERE c.status = 'pending' AND o.status = 'open'
                """, Long.class);
        return n == null ? 0 : n;
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
