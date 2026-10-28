package org.dacnpm.schedulemanager.api.notifications;

import java.util.List;

/** Trang thông báo có phân trang (page bắt đầu từ 0). */
public record NotificationPage(List<NotificationView> items, int page, int size, long total, long unread) {}
