package org.dacnpm.schedulemanager.schedule.model;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Đăng ký thời gian rảnh của nhân viên trong một kỳ.
 *
 * @param submitted   false nếu đăng ký còn là nháp (chưa gửi)
 * @param slotsByDate ngày -> các khoảng rảnh trong ngày đó
 */
public record UserAvailability(String userId, boolean submitted, Map<LocalDate, List<TimeRange>> slotsByDate,
                               Map<LocalDate,List<TimeRange>> unavailableByDate) {
    public UserAvailability(String userId,boolean submitted,Map<LocalDate,List<TimeRange>> slotsByDate) {
        this(userId,submitted,slotsByDate,Map.of());
    }

    /** Explicit unavailable intervals always win, including intervals crossing midnight. */
    public static UserAvailability subtract(String userId, boolean submitted,
            Map<LocalDate, List<TimeRange>> available, Map<LocalDate, List<TimeRange>> blocked) {
        Map<LocalDate, List<TimeRange>> allowed = daily(available);
        Map<LocalDate, List<TimeRange>> denied = daily(blocked);
        Map<LocalDate, List<TimeRange>> result = new HashMap<>();
        allowed.forEach((date, ranges) -> {
            List<TimeRange> parts = new java.util.ArrayList<>(ranges);
            for (TimeRange cut : denied.getOrDefault(date, List.of())) {
                List<TimeRange> next = new java.util.ArrayList<>();
                for (TimeRange part : parts) {
                    if (cut.endMinute() <= part.startMinute() || cut.startMinute() >= part.endMinute()) next.add(part);
                    else {
                        if (part.startMinute() < cut.startMinute()) next.add(new TimeRange(part.startMinute(), cut.startMinute()));
                        if (cut.endMinute() < part.endMinute()) next.add(new TimeRange(cut.endMinute(), part.endMinute()));
                    }
                }
                parts = next;
            }
            result.put(date, parts);
        });
        return new UserAvailability(userId, submitted, result, denied);
    }

    private static Map<LocalDate, List<TimeRange>> daily(Map<LocalDate, List<TimeRange>> source) {
        Map<LocalDate, List<TimeRange>> result = new HashMap<>();
        if (source == null) return result;
        source.forEach((date, ranges) -> ranges.forEach(range -> {
            int start = range.startMinute();
            while (start < range.endMinute()) {
                int offset = start / 1440;
                int end = Math.min(range.endMinute(), (offset + 1) * 1440);
                result.computeIfAbsent(date.plusDays(offset), d -> new java.util.ArrayList<>())
                        .add(new TimeRange(start - offset * 1440, end - offset * 1440));
                start = end;
            }
        }));
        return result;
    }

    public UserAvailability {
        Map<LocalDate, List<TimeRange>> copy = new HashMap<>();
        if (slotsByDate != null) {
            slotsByDate.forEach((date, slots) -> copy.put(date, slots == null ? List.of() : List.copyOf(slots)));
        }
        slotsByDate = Map.copyOf(copy);
        Map<LocalDate,List<TimeRange>> deniedCopy=new HashMap<>();
        if(unavailableByDate!=null)unavailableByDate.forEach((date,ranges)->deniedCopy.put(date,List.copyOf(ranges)));
        unavailableByDate=Map.copyOf(deniedCopy);
    }
}
