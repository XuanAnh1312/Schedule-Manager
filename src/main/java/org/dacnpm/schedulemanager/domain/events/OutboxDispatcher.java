package org.dacnpm.schedulemanager.domain.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dacnpm.schedulemanager.api.notifications.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Đọc outbox và tạo thông báo in-app cho từng người nhận (BA-05, BA-06).
 *
 * <ul>
 *   <li><b>Chỉ sự kiện đã commit</b> mới nằm trong outbox → không thông báo cho giao dịch rollback.</li>
 *   <li><b>Chống trùng</b>: notifications có UNIQUE(event_id, recipient_id) và câu lệnh insert
 *       dạng NOT EXISTS; chạy lại một sự kiện không tạo thông báo thứ hai.</li>
 *   <li><b>Retry</b>: lỗi → status {@code failed}, thử lại sau 2^n phút; vượt {@code maxAttempts}
 *       → {@code dead} để admin kiểm tra ({@code last_error}).</li>
 *   <li><b>Nhiều instance</b>: mỗi sự kiện được "claim" bằng UPDATE có điều kiện trạng thái,
 *       kèm thời hạn lease; sự kiện kẹt ở {@code processing} quá hạn được trả về hàng đợi.</li>
 * </ul>
 */
@Profile("sqlserver")
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int LEASE_SECONDS = 600;

    private final JdbcTemplate jdbc;
    private final NotificationRepository notifications;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxDispatcher(JdbcTemplate jdbc,
                            NotificationRepository notifications,
                            TransactionTemplate tx,
                            ObjectMapper objectMapper,
                            @Value("${app.events.batch-size:50}") int batchSize,
                            @Value("${app.events.max-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.events.poll-ms:5000}")
    public void poll() {
        recoverExpiredLeases();
        List<String> ids = jdbc.queryForList("""
                SELECT TOP (?) id FROM domain_events
                WHERE status IN ('pending', 'failed') AND next_attempt_at <= SYSUTCDATETIME()
                ORDER BY occurred_at
                """, String.class, batchSize);
        for (String id : ids) {
            dispatchOne(id);
        }
    }

    /** Xử lý một sự kiện; public để test và để admin chạy lại thủ công nếu cần. */
    public boolean dispatchOne(String eventId) {
        int claimed = jdbc.update("""
                UPDATE domain_events
                SET status = 'processing', attempts = attempts + 1,
                    next_attempt_at = DATEADD(SECOND, ?, SYSUTCDATETIME())
                WHERE id = ? AND status IN ('pending', 'failed')
                """, LEASE_SECONDS, eventId);
        if (claimed == 0) {
            return false; // instance khác đã nhận
        }
        try {
            tx.executeWithoutResult(status -> deliver(eventId));
            return true;
        } catch (RuntimeException ex) {
            markFailed(eventId, ex);
            return false;
        }
    }

    private void deliver(String eventId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT id, event_type, entity_type, entity_id, recipients_json, title, body, link_route
                FROM domain_events WHERE id = ?
                """, eventId);
        for (String recipient : resolveRecipients((String) row.get("recipients_json"))) {
            notifications.insertIfAbsent(
                    eventId, recipient,
                    (String) row.get("event_type"),
                    (String) row.get("title"),
                    (String) row.get("body"),
                    (String) row.get("entity_type"),
                    (String) row.get("entity_id"),
                    (String) row.get("link_route"));
        }
        jdbc.update("""
                UPDATE domain_events SET status = 'processed', processed_at = SYSUTCDATETIME(), last_error = NULL
                WHERE id = ?
                """, eventId);
    }

    /** Mở rộng "role:admin" và loại người nhận không tồn tại/đã khóa. */
    Set<String> resolveRecipients(String recipientsJson) {
        List<String> raw;
        try {
            raw = objectMapper.readValue(recipientsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("recipients_json không hợp lệ", e);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String r : raw) {
            if (DomainEvent.ROLE_ADMIN.equals(r)) {
                result.addAll(jdbc.queryForList(
                        "SELECT id FROM users WHERE role = 'admin' AND status = 'active'", String.class));
            } else if (r != null && !r.isBlank()) {
                Integer active = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE id = ? AND status = 'active'", Integer.class, r);
                if (active != null && active > 0) {
                    result.add(r);
                }
            }
        }
        return result;
    }

    private void markFailed(String eventId, RuntimeException ex) {
        log.warn("Gửi sự kiện {} thất bại: {}", eventId, ex.getMessage());
        String message = String.valueOf(ex.getMessage());
        if (message.length() > 1000) message = message.substring(0, 1000);
        jdbc.update("""
                UPDATE domain_events
                SET status = CASE WHEN attempts >= ? THEN 'dead' ELSE 'failed' END,
                    next_attempt_at = DATEADD(MINUTE, POWER(2, CASE WHEN attempts > 10 THEN 10 ELSE attempts END),
                                              SYSUTCDATETIME()),
                    last_error = ?
                WHERE id = ?
                """, maxAttempts, message, eventId);
    }

    private void recoverExpiredLeases() {
        jdbc.update("""
                UPDATE domain_events SET status = 'failed', last_error = N'Lease hết hạn (instance dừng giữa chừng)'
                WHERE status = 'processing' AND next_attempt_at < SYSUTCDATETIME()
                """);
    }
}
