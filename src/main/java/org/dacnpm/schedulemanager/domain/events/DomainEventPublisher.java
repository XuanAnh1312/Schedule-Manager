package org.dacnpm.schedulemanager.domain.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi sự kiện vào bảng outbox {@code domain_events} <b>trong cùng giao dịch</b> với thay đổi
 * nghiệp vụ (BA-06). Vì vậy:
 * <ul>
 *   <li>giao dịch rollback → sự kiện cũng biến mất, không có thông báo "ma";</li>
 *   <li>giao dịch commit → sự kiện chắc chắn còn đó để {@link OutboxDispatcher} gửi, kể cả khi
 *       server tắt ngay sau commit.</li>
 * </ul>
 *
 * <p>Cách dùng từ module khác (ví dụ Tường khi công bố lịch):
 * <pre>{@code
 * @Transactional
 * public void publish(...) {
 *     ... cập nhật lịch ...
 *     events.publish(new DomainEvent(DomainEvent.idOf(EventTypes.SCHEDULE_PUBLISHED, planId, version),
 *         EventTypes.SCHEDULE_PUBLISHED, "schedule_plan", planId, version, actorId,
 *         affectedUserIds, "Lịch mới đã được công bố", "...", "my-schedule"));
 * }
 * }</pre>
 */
@Profile("sqlserver")
@Component
public class DomainEventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DomainEventPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * @return {@code true} nếu sự kiện mới được ghi; {@code false} nếu eventId đã tồn tại (bỏ qua, chống trùng).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean publish(DomainEvent event) {
        if (event.recipients().isEmpty()) {
            return false;
        }
        // INSERT ... WHERE NOT EXISTS: publish lặp với cùng eventId là no-op.
        int inserted;
        try {
            inserted = jdbc.update("""
                INSERT INTO domain_events (id, event_type, entity_type, entity_id, entity_version,
                                           actor_id, recipients_json, title, body, link_route)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM domain_events WHERE id = ?)
                """,
                event.eventId(), event.type(), event.entityType(), event.entityId(), event.entityVersion(),
                event.actorId(), toJson(event), truncate(event.title(), 200), truncate(event.body(), 1000),
                event.linkRoute(), event.eventId());
        } catch (DuplicateKeyException race) {
            // Hai giao dịch publish cùng eventId đồng thời: bản sau bị PK chặn → coi như đã có.
            return false;
        }
        return inserted == 1;
    }

    private String toJson(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event.recipients());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể serialize người nhận", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
