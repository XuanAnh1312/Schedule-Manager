package org.dacnpm.schedulemanager.api.notifications;

import java.util.List;

/**
 * Cấu hình nhắc hạn đăng ký.
 *
 * @param deadlineReminderHours các mốc nhắc trước hạn (giờ). Rỗng = chưa cấu hình → không nhắc.
 * @param configured            false khi Admin chưa cấu hình (hiển thị cảnh báo, không tự điền giá trị).
 */
public record NotificationSettings(List<Integer> deadlineReminderHours, boolean configured) {}
