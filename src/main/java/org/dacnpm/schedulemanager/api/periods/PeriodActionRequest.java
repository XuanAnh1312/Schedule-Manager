package org.dacnpm.schedulemanager.api.periods;

import java.time.LocalDateTime;

/**
 * Body cho các thao tác chuyển trạng thái.
 *
 * @param version     nếu gửi, phải khớp version hiện tại (chống thao tác trên dữ liệu cũ)
 * @param confirm     bắt buộc {@code true} khi mở lại kỳ
 * @param reason      bắt buộc khi mở lại kỳ; được ghi vào lịch sử
 * @param newDeadline hạn mới khi mở lại (giờ địa phương nghiệp vụ); bắt buộc nếu kỳ tự khóa
 */
public record PeriodActionRequest(
        Integer version,
        Boolean confirm,
        String reason,
        LocalDateTime newDeadline) {

    public static PeriodActionRequest empty() {
        return new PeriodActionRequest(null, null, null, null);
    }
}
