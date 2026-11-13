package org.dacnpm.schedulemanager.api.events;

import java.time.LocalDateTime;

/**
 * Một dòng outbox {@code domain_events} cho màn hình vận hành (BAO-01). Thời gian là UTC.
 *
 * @param notificationsCreated số thông báo in-app đã tạo từ sự kiện này (để kiểm tra không gửi trùng)
 */
public record OutboxEventView(
        String id,
        String type,
        String entityType,
        String entityId,
        int entityVersion,
        String actorId,
        String title,
        String status,
        int attempts,
        LocalDateTime occurredAt,
        LocalDateTime nextAttemptAt,
        LocalDateTime processedAt,
        String lastError,
        long notificationsCreated
) {}
