package org.dacnpm.schedulemanager.api.openshifts;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Ca trống. {@code claimDeadlineUtc} là thời điểm UTC; client tự hiển thị theo giờ địa phương.
 * {@code myClaimStatus}/{@code myClaimId} chỉ có giá trị khi nhân viên xem.
 */
public record OpenShiftView(
        String id,
        String shiftId,
        String shiftName,
        LocalDate shiftDate,
        LocalTime startTime,
        LocalTime endTime,
        int slots,
        int filledCount,
        String requiredLevelId,
        LocalDateTime claimDeadlineUtc,
        String note,
        String status,
        int pendingClaims,
        String myClaimId,
        String myClaimStatus,
        int version,
        LocalDateTime createdAt
) {}
