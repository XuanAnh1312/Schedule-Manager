package org.dacnpm.schedulemanager.api.approvals;

import java.time.LocalDateTime;

/** Cấu hình quy trình duyệt (BAO-04). {@code leaveTypeId} null = mặc định của loại đối tượng. */
public record ApprovalFlow(String id, String subjectType, String leaveTypeId, String leaveTypeName, String mode,
                           String updatedBy, LocalDateTime updatedAt, int version) {

    public static final String ADMIN_ONLY = "admin_only";
    public static final String MANAGER = "manager";
    public static final String MANAGER_THEN_ADMIN = "manager_then_admin";
}
