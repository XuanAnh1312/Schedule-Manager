package org.dacnpm.schedulemanager.schedule.model;

/**
 * Phân công một nhân viên vào một ca (bảng assignments).
 *
 * @param status confirmed hoặc cancelled; chỉ phân công confirmed được tính giờ
 */
public record Assignment(String id, String shiftId, String userId, String status) {

    public static final String CONFIRMED = "confirmed";
    public static final String CANCELLED = "cancelled";

    public boolean active() {
        return CONFIRMED.equals(status);
    }
}
