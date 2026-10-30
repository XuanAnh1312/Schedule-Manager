package org.dacnpm.schedulemanager.schedule.model;

import java.time.LocalTime;

/**
 * Khoảng thời gian trong ngày tính theo phút từ 00:00. endMinute có thể vượt 1440 khi khoảng thời gian
 * kéo sang ngày hôm sau.
 */
public record TimeRange(int startMinute, int endMinute) {

    public TimeRange {
        if (startMinute < 0 || endMinute <= startMinute) {
            throw new IllegalArgumentException("Khoảng thời gian không hợp lệ: " + startMinute + "-" + endMinute + ".");
        }
    }

    /** Giờ kết thúc không sau giờ bắt đầu nghĩa là kết thúc ngày hôm sau. */
    public static TimeRange of(LocalTime start, LocalTime end) {
        int s = start.getHour() * 60 + start.getMinute();
        int e = end.getHour() * 60 + end.getMinute();
        return new TimeRange(s, e <= s ? e + 1440 : e);
    }
}
