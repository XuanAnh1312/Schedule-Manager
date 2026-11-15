package org.dacnpm.schedulemanager.domain.schedule;

/**
 * Một vi phạm khi kiểm tra phân công.
 *
 * @param code        mã ổn định (OVERLAP, MIN_REST, MAX_HOURS_DAY, …)
 * @param message     mô tả tiếng Việt cho người dùng
 * @param blocking    true = chặn thao tác nếu không có ngoại lệ hợp lệ
 * @param overridable true = thuộc nhóm có thể xin ngoại lệ (TW-06: thời gian rảnh, ngày không thể làm). Vi phạm
 *                    blocking nhưng không overridable là ràng buộc cứng, không bao giờ được duyệt (BAO-02).
 */
public record Violation(String code, String message, boolean blocking, boolean overridable) {

    public Violation(String code, String message, boolean blocking) {
        this(code, message, blocking, false);
    }

    public static Violation block(String code, String message) {
        return new Violation(code, message, true, false);
    }

    public static Violation warn(String code, String message) {
        return new Violation(code, message, false, false);
    }

    /** Vi phạm chặn thao tác nhưng người có quyền ngoại lệ được xem trước, xác nhận và duyệt kèm lý do. */
    public static Violation exceptionable(String code, String message) {
        return new Violation(code, message, true, true);
    }

    /** Ràng buộc cứng: chặn và không có đường ngoại lệ. */
    public boolean hard() {
        return blocking && !overridable;
    }
}
