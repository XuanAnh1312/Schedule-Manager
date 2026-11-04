package org.dacnpm.schedulemanager.api.requests;

import java.time.LocalDateTime;

/**
 * Yêu cầu đổi ca / làm thay. Lưu đủ: người yêu cầu, người thay, ca gốc, thời điểm,
 * người duyệt, quyết định, lý do và người chịu trách nhiệm cuối (mục 3.2).
 */
public record ShiftRequestView(
        String id,
        String type,
        String status,
        String requesterId,
        String requesterName,
        String sourceAssignmentId,
        ShiftSummary sourceShift,
        String targetUserId,
        String targetUserName,
        String targetAssignmentId,
        ShiftSummary targetShift,
        String reason,
        String peerNote,
        LocalDateTime peerRespondedAt,
        String decidedBy,
        String decidedByName,
        LocalDateTime decidedAt,
        String decisionNote,
        String finalResponsibleUserId,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public boolean isOpen() {
        return ShiftRequestStatus.PENDING_PEER.equals(status) || ShiftRequestStatus.PENDING_APPROVAL.equals(status);
    }
}
