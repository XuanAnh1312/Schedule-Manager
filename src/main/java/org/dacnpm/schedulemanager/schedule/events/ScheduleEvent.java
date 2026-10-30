package org.dacnpm.schedulemanager.schedule.events;

import java.util.List;
import java.util.Objects;

/**
 * Sự kiện lịch gửi cho module thông báo (Bảo). Các trường khớp một-một với {@code DomainEvent} của module đó, nên
 * adapter chỉ cần chép sang.
 *
 * @param eventId       tất định theo (loại, thực thể, phiên bản): phát lặp không tạo thông báo thứ hai
 * @param entityVersion phiên bản thực thể sau thay đổi
 * @param recipients    userId người nhận
 * @param linkRoute     route SPA mở khi bấm thông báo
 */
public record ScheduleEvent(String eventId, String type, String entityType, String entityId, int entityVersion,
                            String actorId, List<String> recipients, String title, String body, String linkRoute) {

    /** Tên loại sự kiện thống nhất với EventTypes của module thông báo. */
    public static final String SCHEDULE_PUBLISHED = "schedule.published";
    public static final String SCHEDULE_CHANGED = "schedule.changed";
    public static final String ASSIGNMENT_ADDED = "assignment.added";

    public ScheduleEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        recipients = recipients == null ? List.of() : recipients.stream().filter(Objects::nonNull).distinct().toList();
    }

    public static String idOf(String type, String entityId, int entityVersion) {
        return type + ":" + entityId + ":v" + entityVersion;
    }

    public static ScheduleEvent of(String type, String entityType, String entityId, int entityVersion, String actorId,
                                   List<String> recipients, String title, String body, String linkRoute) {
        return new ScheduleEvent(idOf(type, entityId, entityVersion), type, entityType, entityId, entityVersion,
                actorId, recipients, title, body, linkRoute);
    }
}
