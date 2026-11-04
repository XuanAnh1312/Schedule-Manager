package org.dacnpm.schedulemanager.api.openshifts;

import java.time.LocalDateTime;

public record ClaimView(
        String id,
        String openShiftId,
        String userId,
        String userName,
        String status,
        String note,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionNote,
        String assignmentId,
        int version,
        LocalDateTime createdAt
) {}
