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
        LocalDateTime createdAt
) {
    public long days() {
        return java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }
}
