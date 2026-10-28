package org.dacnpm.schedulemanager.domain.events;

import java.util.List;
import java.util.Objects;

/**
 * Hợp đồng sự kiện nghiệp vụ dùng chung cho mọi module (BA-06).
 *
 * <p><b>eventId phải tất định</b> theo (loại, thực thể, phiên bản) để publish lặp — ví dụ
 * duyệt hai lần, retry request — không tạo thêm sự kiện/thông báo. Dùng {@link #idOf}.
 *
 * <p>{@code recipients} là danh sách userId; có thể dùng {@link #ROLE_ADMIN} để gửi
 * tất cả admin đang hoạt động (giải quyết lúc dispatch).
 *
 * @param eventId       khóa duy nhất của sự kiện
 * @param type          loại sự kiện, xem {@link EventTypes}
 * @param entityType    loại thực thể, ví dụ {@code shift_request}
 * @param entityId      id thực thể
 * @param entityVersion phiên bản thực thể sau thay đổi
 * @param actorId       người gây ra sự kiện (null = hệ thống)
 * @param recipients    người nhận thông báo
 * @param title         tiêu đề hiển thị
 * @param body          nội dung hiển thị
 * @param linkRoute     route SPA để mở khi bấm thông báo (ví dụ {@code my-requests})
 */
public record DomainEvent(
        String eventId,
        String type,
        String entityType,
        String entityId,
        int entityVersion,
        String actorId,
        List<String> recipients,
        String title,
        String body,
        String linkRoute
) {
    public static final String ROLE_ADMIN = "role:admin";

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        if (eventId.length() > 160) {
            throw new IllegalArgumentException("eventId quá dài (tối đa 160 ký tự)");
        }
    }

    public static String idOf(String type, String entityId, int entityVersion) {
        return type + ":" + entityId + ":v" + entityVersion;
    }
}
