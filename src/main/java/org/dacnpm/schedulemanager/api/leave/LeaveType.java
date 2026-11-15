package org.dacnpm.schedulemanager.api.leave;

/** Loại nghỉ do Admin cấu hình (bảng leave_types). */
public record LeaveType(String id, String name, String description, boolean paid, boolean requiresDocument,
                        int minNoticeDays, boolean active, boolean tracksBalance) {

    /** Loại nghỉ không trừ sổ phép (giữ tương thích chỗ gọi cũ). */
    public LeaveType(String id, String name, String description, boolean paid, boolean requiresDocument,
                     int minNoticeDays, boolean active) {
        this(id, name, description, paid, requiresDocument, minNoticeDays, active, false);
    }
}
