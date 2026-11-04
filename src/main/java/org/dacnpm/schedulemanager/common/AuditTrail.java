package org.dacnpm.schedulemanager.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ghi audit vào bảng {@code history} (V5) trong cùng giao dịch với thay đổi nghiệp vụ (AC-12).
 *
 * <p>Tạm thời thuộc module của Bảo; khi Anh hoàn thiện hạ tầng audit chung,
 * chỉ cần thay phần thân {@link #record} — chữ ký giữ nguyên.
 */
@Profile("sqlserver")
@Component
public class AuditTrail {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditTrail(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(String eventType, String title, String detail, String actorId,
                       Object before, Object after, String reason) {
        String actorName = actorId == null ? "system" : jdbc.query(
                "SELECT full_name FROM users WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : "unknown", actorId);
        jdbc.update("""
                INSERT INTO history (id, event_type, title, detail, actor_id, actor_name,
                                     before_json, after_json, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Ids.next("hist"), eventType, title, detail, actorId, actorName,
                toJson(before), toJson(after), reason);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể ghi audit JSON", e);
        }
    }
}
