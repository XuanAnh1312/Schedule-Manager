package org.dacnpm.schedulemanager.api.comms;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thông báo chung, mẫu tin và lịch sử đọc (BAO-05). */
@Profile("sqlserver")
@Repository
public class AnnouncementRepository {

    public record Template(String id, String name, String title, String body, boolean active, int version) {}

    public record Announcement(String id, String title, String body, String scopeType, String scopeIdsJson,
                               String templateId, boolean sendEmail, boolean mandatory, int recipientsCount,
                               String createdBy, String createdByName, LocalDateTime createdAt, long readCount) {}

    private final JdbcTemplate jdbc;

    public AnnouncementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ── Mẫu tin ────────────────────────────────────────────────────────── */

    public List<Template> templates(boolean activeOnly) {
        return jdbc.query("SELECT * FROM announcement_templates WHERE (? = 0 OR active = 1) ORDER BY name",
                (rs, i) -> new Template(rs.getString("id"), rs.getString("name"), rs.getString("title"),
                        rs.getString("body"), rs.getBoolean("active"), rs.getInt("version")), activeOnly ? 1 : 0);
    }

    public Optional<Template> template(String id) {
        return templates(false).stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public void insertTemplate(String id, String name, String title, String body, String actorId) {
        jdbc.update("""
                INSERT INTO announcement_templates (id, name, title, body, created_by) VALUES (?, ?, ?, ?, ?)
                """, id, name, title, body, actorId);
    }

    public int updateTemplate(String id, int expectedVersion, String name, String title, String body, boolean active) {
        return jdbc.update("""
                UPDATE announcement_templates SET name = ?, title = ?, body = ?, active = ?,
                       updated_at = SYSUTCDATETIME(), version = version + 1
                WHERE id = ? AND version = ?
                """, name, title, body, active, id, expectedVersion);
    }

    /* ── Người nhận theo phạm vi ────────────────────────────────────────── */

    /** Nhân viên + Admin đang hoạt động trong phạm vi. {@code ids} rỗng với company. */
    public List<String> recipients(String scopeType, List<String> ids) {
        String base = "SELECT id FROM users WHERE status = 'active' AND archived_at IS NULL";
        if ("company".equals(scopeType)) {
            return jdbc.queryForList(base + " ORDER BY id", String.class);
        }
        String column = switch (scopeType) {
            case "branch" -> "branch_id";
            case "department" -> "department_id";
            case "group" -> "group_id";
            case "users" -> "id";
            default -> throw new IllegalArgumentException(scopeType);
        };
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        return jdbc.queryForList(base + " AND " + column + " IN (" + placeholders + ") ORDER BY id", String.class,
                ids.toArray());
    }

    /* ── Thông báo chung ────────────────────────────────────────────────── */

    public void insert(String id, String title, String body, String scopeType, String scopeJson, String templateId,
                       boolean sendEmail, boolean mandatory, int recipients, String actorId) {
        jdbc.update("""
                INSERT INTO announcements (id, title, body, scope_type, scope_ids_json, template_id, send_email,
                                           mandatory, recipients_count, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, title, body, scopeType, scopeJson, templateId, sendEmail, mandatory, recipients, actorId);
    }

    private static final String SELECT = """
            SELECT a.*, u.full_name AS created_by_name,
                   (SELECT COUNT(*) FROM notifications n
                     WHERE n.entity_type = 'announcement' AND n.entity_id = a.id AND n.is_read = 1) AS read_count
            FROM announcements a JOIN users u ON u.id = a.created_by
            """;

    private Announcement map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Announcement(rs.getString("id"), rs.getString("title"), rs.getString("body"),
                rs.getString("scope_type"), rs.getString("scope_ids_json"), rs.getString("template_id"),
                rs.getBoolean("send_email"), rs.getBoolean("mandatory"), rs.getInt("recipients_count"),
                rs.getString("created_by"), rs.getString("created_by_name"), ts(rs.getTimestamp("created_at")),
                rs.getLong("read_count"));
    }

    public Optional<Announcement> find(String id) {
        return jdbc.query(SELECT + " WHERE a.id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    public List<Announcement> findAll(int limit) {
        return jdbc.query(SELECT + " ORDER BY a.created_at DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY",
                (rs, i) -> map(rs), limit);
    }

    /** Thông báo chung tôi đã nhận (qua thông báo in-app) kèm trạng thái đã đọc. */
    public List<Map<String, Object>> findForRecipient(String userId, int limit) {
        return jdbc.queryForList("""
                SELECT a.id, a.title, a.body, a.created_at AS createdAt, u.full_name AS createdByName,
                       n.id AS notificationId, n.is_read AS isRead, n.read_at AS readAt
                FROM notifications n
                JOIN announcements a ON a.id = n.entity_id
                JOIN users u ON u.id = a.created_by
                WHERE n.entity_type = 'announcement' AND n.recipient_id = ?
                ORDER BY a.created_at DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
                """, userId, limit);
    }

    /** Lịch sử đọc: mọi người nhận của thông báo, đã đọc lúc nào; kèm trạng thái email nếu có. */
    public List<Map<String, Object>> reads(String announcementId) {
        return jdbc.queryForList("""
                SELECT n.recipient_id AS userId, u.full_name AS fullName, n.is_read AS isRead, n.read_at AS readAt,
                       e.status AS emailStatus, e.sent_at AS emailSentAt
                FROM notifications n
                JOIN users u ON u.id = n.recipient_id
                LEFT JOIN email_deliveries e ON e.event_id = n.event_id AND e.recipient_id = n.recipient_id
                WHERE n.entity_type = 'announcement' AND n.entity_id = ?
                ORDER BY n.is_read, u.full_name
                """, announcementId);
    }

    public boolean hasNotification(String announcementId, String userId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications WHERE entity_type = 'announcement' AND entity_id = ? AND recipient_id = ?
                """, Integer.class, announcementId, userId);
        return n != null && n > 0;
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
