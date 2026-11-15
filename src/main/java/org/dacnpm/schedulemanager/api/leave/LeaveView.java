package org.dacnpm.schedulemanager.api.leave;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record LeaveView(
        String id,
        String userId,
        String userName,
        String leaveTypeId,
        String leaveTypeName,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        String documentUrl,
        String status,
        String decidedBy,
        String decidedByName,
        LocalDateTime decidedAt,
        String decisionNote,
        int affectedAssignmentCount,
        int version,
        LocalDateTime createdAt,
        boolean halfDay,
        java.math.BigDecimal daysRequested
) {
    public long days() {
        return java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /** Số ngày nghỉ (BAO-03): nửa ngày = 0,5; đơn cũ chưa lưu số ngày thì tính theo khoảng ngày. */
    public java.math.BigDecimal leaveDays() {
        if (daysRequested != null) return daysRequested;
        return halfDay ? new java.math.BigDecimal("0.5") : java.math.BigDecimal.valueOf(days());
    }
}
