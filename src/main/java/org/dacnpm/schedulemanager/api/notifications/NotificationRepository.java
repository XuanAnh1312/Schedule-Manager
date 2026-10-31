package org.dacnpm.schedulemanager.api.notifications;

import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Profile("sqlserver")
@Repository
public class NotificationRepository {

    private static final RowMapper<NotificationView> MAPPER = (rs, i) -> new NotificationView(
            rs.getString("id"),
            rs.getString("notification_type"),
            rs.getString("title"),
            rs.getString("body"),
            rs.getString("entity_type"),
            rs.getString("entity_id"),
            rs.getString("link_route"),
            rs.getBoolean("is_read"),
            toLocal(rs.getTimestamp("read_at")),
            toLocal(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tạo thông báo nếu (event, người nhận) chưa có. Trả về true nếu tạo mới. */
    public boolean insertIfAbsent(String eventId, String recipientId, String type, String title, String body,
                                  String entityType, String entityId, String linkRoute) {
        try {
            return jdbc.update("""
                    INSERT INTO notifications (id, recipient_id, event_id, notification_type, title, body,
                                               entity_type, entity_id, link_route)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM notifications WHERE event_id = ? AND recipient_id = ?)
                    """,
                    Ids.next("ntf"), recipientId, eventId, type, title, body, entityType, entityId, linkRoute,
                    eventId, recipientId) == 1;
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    public List<NotificationView> findByRecipient(String recipientId, boolean unreadOnly, int offset, int limit) {
        return jdbc.query("""
                SELECT id, notification_type, title, body, entity_type, entity_id, link_route,
                       is_read, read_at, created_at
                FROM notifications
                WHERE recipient_id = ? AND (? = 0 OR is_read = 0)
                ORDER BY created_at DESC, id
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, MAPPER, recipientId, unreadOnly ? 1 : 0, offset, limit);
    }

    public long countByRecipient(String recipientId, boolean unreadOnly) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND (? = 0 OR is_read = 0)",
                Long.class, recipientId, unreadOnly ? 1 : 0);
        return count == null ? 0 : count;
    }

    /** Chỉ đánh dấu được thông báo của chính mình (điều kiện recipient_id). */
    public int markRead(String id, String recipientId) {
        return jdbc.update("""
                UPDATE notifications SET is_read = 1, read_at = SYSUTCDATETIME()
                WHERE id = ? AND recipient_id = ? AND is_read = 0
                """, id, recipientId);
    }

    public boolean existsForRecipient(String id, String recipientId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE id = ? AND recipient_id = ?", Integer.class, id, recipientId);
        return n != null && n > 0;
    }

    public int markAllRead(String recipientId) {
        return jdbc.update("""
                UPDATE notifications SET is_read = 1, read_at = SYSUTCDATETIME()
                WHERE recipient_id = ? AND is_read = 0
                """, recipientId);
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
