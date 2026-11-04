package org.dacnpm.schedulemanager.domain.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Thông tin ca và độ phủ hiện tại (số phân công confirmed). */
public record ShiftInfo(
        String shiftId,
        String name,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String status,
        int totalRequired,
        int confirmedCount
) {
    public LocalDateTime startsAt() {
        return LocalDateTime.of(date, startTime);
    }

    /** Ca qua đêm (kết thúc ≤ bắt đầu) kết thúc vào ngày hôm sau. */
    public LocalDateTime endsAt() {
        LocalDateTime end = LocalDateTime.of(date, endTime);
        return endTime.isAfter(startTime) ? end : end.plusDays(1);
    }

    public double hours() {
        return java.time.Duration.between(startsAt(), endsAt()).toMinutes() / 60.0;
    }

    public int missing() {
        return Math.max(0, totalRequired - confirmedCount);
    }

    public String label() {
        return name + " " + date + " " + startTime + "–" + endTime;
    }
}
