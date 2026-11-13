package org.dacnpm.schedulemanager.api.events;

import java.util.List;
import java.util.Map;

/**
 * Trang sự kiện outbox (page bắt đầu từ 0).
 *
 * @param statusCounts số sự kiện theo trạng thái trên toàn bảng (không phụ thuộc bộ lọc), dùng cho thẻ tổng quan
 */
public record OutboxEventPage(
        List<OutboxEventView> items,
        int page,
        int size,
        long total,
        Map<String, Long> statusCounts
) {}
