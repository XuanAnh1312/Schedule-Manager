package org.dacnpm.schedulemanager.api.periods;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Một dòng {@code periods} kèm các bảng con. Các mốc thời gian ({@code *Utc}) là UTC như trong DB.
 *
 * @param requiredDaysOfWeek thứ cần đăng ký, 0 = Chủ nhật … 6 = Thứ bảy (giống SPA)
 * @param applicableUserIds  rỗng = áp dụng cho mọi nhân viên đang hoạt động
 */
public record PeriodRecord(
        String id,
        String name,
        String cycle,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime openAtUtc,
        LocalDateTime deadlineUtc,
        boolean autoLock,
        boolean allowReopenByAdmin,
        PeriodStatus status,
        int version,
        LocalDateTime createdAtUtc,
        LocalDateTime updatedAtUtc,
        List<Integer> requiredDaysOfWeek,
        List<LocalDate> exemptDates,
        List<LocalDate> closedDates,
        List<String> applicableUserIds) {

    public PeriodRecord withChildren(List<Integer> weekdays, List<LocalDate> exempt,
                                     List<LocalDate> closed, List<String> applicable) {
        return new PeriodRecord(id, name, cycle, startDate, endDate, openAtUtc, deadlineUtc, autoLock,
                allowReopenByAdmin, status, version, createdAtUtc, updatedAtUtc,
                List.copyOf(weekdays), List.copyOf(exempt), List.copyOf(closed), List.copyOf(applicable));
    }

    public PeriodRecord withStatus(PeriodStatus newStatus, LocalDateTime newDeadlineUtc) {
        return new PeriodRecord(id, name, cycle, startDate, endDate, openAtUtc, newDeadlineUtc, autoLock,
                allowReopenByAdmin, newStatus, version + 1, createdAtUtc, updatedAtUtc,
                requiredDaysOfWeek, exemptDates, closedDates, applicableUserIds);
    }
}
