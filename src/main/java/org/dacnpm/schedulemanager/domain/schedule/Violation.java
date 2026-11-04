package org.dacnpm.schedulemanager.domain.schedule;

/**
 * Một vi phạm khi kiểm tra phân công.
 *
 * @param code     mã ổn định (OVERLAP, MIN_REST, MAX_HOURS_DAY, …)
 * @param message  mô tả tiếng Việt cho người dùng
 * @param blocking true = ràng buộc bắt buộc, không được duyệt
 */
public record Violation(String code, String message, boolean blocking) {

    public static Violation block(String code, String message) {
        return new Violation(code, message, true);
    }

    public static Violation warn(String code, String message) {
        return new Violation(code, message, false);
    }
}
