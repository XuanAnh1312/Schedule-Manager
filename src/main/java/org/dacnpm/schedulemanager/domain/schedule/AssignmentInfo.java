package org.dacnpm.schedulemanager.domain.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Phân công kèm thông tin ca, dùng chung cho đổi ca/nghỉ phép/ca trống. */
public record AssignmentInfo(
        String assignmentId,
        String shiftId,
        String userId,
        String assignmentStatus,
        String shiftName,
        LocalDate shiftDate,
        LocalTime startTime,
        LocalTime endTime,
        String shiftStatus
) {
    /** Thời điểm bắt đầu ca theo giờ địa phương. */
    public LocalDateTime startsAt() {
        return LocalDateTime.of(shiftDate, startTime);
    }

    public boolean confirmed() {
        return "confirmed".equals(assignmentStatus);
    }

    public boolean published() {
        return "published".equals(shiftStatus);
    }

    public String label() {
        return shiftName + " " + shiftDate + " " + startTime + "–" + endTime;
    }
}
