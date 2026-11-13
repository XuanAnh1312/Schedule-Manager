package org.dacnpm.schedulemanager.api.events;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bộ lọc đã chuẩn hóa cho truy vấn outbox.
 *
 * @param statuses  trạng thái cần lấy (rỗng = mọi trạng thái)
 * @param type      loại sự kiện chính xác (null = mọi loại)
 * @param fromUtc   occurred_at ≥ fromUtc (null = không giới hạn)
 * @param toUtc     occurred_at &lt; toUtc (null = không giới hạn)
 */
public record OutboxEventFilter(List<String> statuses, String type, LocalDateTime fromUtc, LocalDateTime toUtc) {
    public OutboxEventFilter {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
