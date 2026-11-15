package org.dacnpm.schedulemanager.api.comms;

import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Hàng đợi email (BAO-05). Claim/đánh dấu bằng UPDATE có điều kiện trạng thái — an toàn khi nhiều instance. */
@Profile("sqlserver")
@Repository
public class EmailRepository {

    public record EmailRow(String id, String eventId, String eventType, String recipientId, String recipientName,
                           String email, String subject, String body, String status, int attempts,
                           LocalDateTime nextAttemptAt, String lastError, LocalDateTime sentAt, LocalDateTime createdAt) {}

    private static final String SELECT = """
            SELECT e.*, d.event_type, u.full_name AS recipient_name
            FROM email_deliveries e
            JOIN domain_events d ON d.id = e.event_id
            JOIN users u ON u.id = e.recipient_id
            """;

    private static final RowMapper<EmailRow> MAPPER = (rs, i) -> new EmailRow(rs.getString("id"),
            rs.getString("event_id"), rs.getString("event_type"), rs.getString("recipient_id"),
            rs.getString("recipient_name"), rs.getString("email"), rs.getString("subject"), rs.getString("body"),
            rs.getString("status"), rs.getInt("attempts"), ts(rs.getTimestamp("next_attempt_at")),
            rs.getString("last_error"), ts(rs.getTimestamp("sent_at")), ts(rs.getTimestamp("created_at")));

    static final int LEASE_SECONDS = 300;

    private final JdbcTemplate jdbc;

    public EmailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ── Xếp hàng (gọi từ outbox dispatcher, trong giao dịch của sự kiện) ── */

    /** Email đang hoạt động của người nhận (null nếu chưa có / tài khoản khóa). */
    public Optional<String> emailOf(String userId) {
        return jdbc.query("SELECT email FROM users WHERE id = ? AND status = 'active'",
                (rs, i) -> rs.getString(1), userId).stream().findFirst().filter(e -> e != null && !e.isBlank());
    }

    /** Người dùng đã tắt email cho loại thông báo này chưa. */
    public boolean emailDisabled(String userId, String type) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_preferences
                WHERE user_id = ? AND notification_type = ? AND email_enabled = 0
                """, Integer.class, userId, type);
        return n != null && n > 0;
    }

    /** Thông báo chung: có tick gửi email / bắt buộc không. */
    public Optional<boolean[]> announcementFlags(String announcementId) {
        return jdbc.query("SELECT send_email, mandatory FROM announcements WHERE id = ?",
                (rs, i) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)}, announcementId).stream().findFirst();
    }

    /** INSERT nếu chưa có (UNIQUE event_id + recipient_id là lớp chặn cuối). */
    public int enqueue(String eventId, String recipientId, String email, String subject, String body, String status,
                       String note) {
        return jdbc.update("""
                INSERT INTO email_deliveries (id, event_id, recipient_id, email, subject, body, status, last_error)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM email_deliveries WHERE event_id = ? AND recipient_id = ?)
                """, Ids.next("mail"), eventId, recipientId, email, subject, body, status, note, eventId, recipientId);
    }

    /* ── Gửi ────────────────────────────────────────────────────────────── */

    public List<String> findDue(int limit) {
        return jdbc.queryForList("""
                SELECT TOP (?) id FROM email_deliveries
                WHERE status IN ('pending', 'failed') AND next_attempt_at <= SYSUTCDATETIME()
                ORDER BY created_at
                """, String.class, limit);
    }

    public int claim(String id) {
        return jdbc.update("""
                UPDATE email_deliveries SET status = 'sending', attempts = attempts + 1,
                       next_attempt_at = DATEADD(SECOND, ?, SYSUTCDATETIME())
                WHERE id = ? AND status IN ('pending', 'failed')
                """, LEASE_SECONDS, id);
    }

    public Optional<EmailRow> find(String id) {
        return jdbc.query(SELECT + " WHERE e.id = ?", MAPPER, id).stream().findFirst();
    }

    public void markSent(String id) {
        jdbc.update("""
                UPDATE email_deliveries SET status = 'sent', sent_at = SYSUTCDATETIME(), last_error = NULL
                WHERE id = ? AND status = 'sending'
                """, id);
    }

    /** Lỗi → failed và thử lại sau 2^n phút; vượt số lần → dead (Admin xem và gửi lại). */
    public void markFailed(String id, int maxAttempts, String error) {
        jdbc.update("""
                UPDATE email_deliveries
                SET status = CASE WHEN attempts >= ? THEN 'dead' ELSE 'failed' END,
                    next_attempt_at = DATEADD(MINUTE, POWER(2, CASE WHEN attempts > 10 THEN 10 ELSE attempts END),
                                              SYSUTCDATETIME()),
                    last_error = ?
                WHERE id = ? AND status = 'sending'
                """, maxAttempts, error, id);
    }

    public int recoverExpiredLeases() {
        return jdbc.update("""
                UPDATE email_deliveries SET status = 'failed', last_error = N'Lease hết hạn (instance dừng giữa chừng)'
                WHERE status = 'sending' AND next_attempt_at < SYSUTCDATETIME()
                """);
    }

    /* ── Quản trị ───────────────────────────────────────────────────────── */

    public List<EmailRow> findPage(List<String> statuses, int offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>();
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" WHERE e.status IN (").append(String.join(",", statuses.stream().map(s -> "?").toList())).append(")");
            args.addAll(statuses);
        }
        sql.append(" ORDER BY e.created_at DESC, e.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        args.add(offset);
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(List<String> statuses) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM email_deliveries e");
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" WHERE e.status IN (").append(String.join(",", statuses.stream().map(s -> "?").toList())).append(")");
        }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, statuses == null ? new Object[0] : statuses.toArray());
        return n == null ? 0 : n;
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS n FROM email_deliveries GROUP BY status",
                rs -> { m.put(rs.getString("status"), rs.getLong("n")); });
        return m;
    }

    public void lock(String id) {
        jdbc.queryForList("SELECT id FROM email_deliveries WITH (UPDLOCK, ROWLOCK) WHERE id = ?", String.class, id);
    }

    /** Gửi lại email failed/dead: về pending, đếm lại số lần thử; giữ last_error tới khi gửi được. */
    public int resetForRetry(String id) {
        return jdbc.update("""
                UPDATE email_deliveries SET status = 'pending', attempts = 0, next_attempt_at = SYSUTCDATETIME()
                WHERE id = ? AND status IN ('failed', 'dead')
                """, id);
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
