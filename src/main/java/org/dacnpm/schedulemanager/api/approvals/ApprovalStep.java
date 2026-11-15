package org.dacnpm.schedulemanager.api.approvals;

import java.time.LocalDateTime;

/** Một bước duyệt của đơn nghỉ / yêu cầu đổi ca (BAO-04). */
public record ApprovalStep(
        String id,
        String subjectType,
        String subjectId,
        int stepNo,
        String approverKind,
        String approverId,
        String approverName,
        String status,
        String decidedBy,
        String decidedByName,
        LocalDateTime decidedAt,
        String note,
        int version
) {
    public static final String MANAGER = "manager";
    public static final String ADMIN = "admin";

    public boolean pending() {
        return "pending".equals(status);
    }

    public boolean open() {
        return "pending".equals(status) || "waiting".equals(status);
    }
}
