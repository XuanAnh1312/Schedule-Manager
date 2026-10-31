package org.dacnpm.schedulemanager.api.periods;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Body tạo/sửa kỳ. {@code openAt}/{@code deadline} là giờ địa phương nghiệp vụ
 * (Asia/Ho_Chi_Minh), ví dụ {@code "2026-11-05T23:59"}; server tự đổi sang UTC để lưu.
 *
 * @param version bắt buộc khi sửa (PUT) để chống ghi đè; bỏ qua khi tạo
 */
public record PeriodRequest(
        String name,
        String cycle,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime openAt,
        LocalDateTime deadline,
        Boolean autoLock,
        Boolean allowReopenByAdmin,
        List<Integer> requiredDaysOfWeek,
        List<LocalDate> exemptDates,
        List<LocalDate> closedDates,
        List<String> applicableUserIds,
        Integer version) {
}
