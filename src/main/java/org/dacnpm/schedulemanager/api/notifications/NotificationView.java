package org.dacnpm.schedulemanager.api.notifications;

import java.time.LocalDateTime;

/** Một thông báo trả về cho client (thời gian là UTC). */
public record NotificationView(
        String id,
        String type,
        String title,
        String body,
        String entityType,
        String entityId,
        String linkRoute,
        boolean read,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {}
