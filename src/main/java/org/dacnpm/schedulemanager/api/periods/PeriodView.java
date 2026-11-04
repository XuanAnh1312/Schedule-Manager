package org.dacnpm.schedulemanager.api.periods;

import org.dacnpm.schedulemanager.common.BusinessTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Kỳ trả về cho client. Mốc thời gian là giờ địa phương nghiệp vụ ({@code timezone}).
 *
 * @param status            giá trị lưu DB: draft | open | locked | archived
 * @param registrationState trạng thái cửa sổ đăng ký tính theo giờ server:
 *                          hidden | upcoming | open | closed | archived
 * @param statusLabel       nhãn hiển thị, ví dụ "Đã đóng đăng ký — hạn chót 05/10/2026 23:59"
 * @param manualClose       {@code true} khi kỳ không tự khóa (chỉ đóng thủ công)
 * @param applicableUserIds chỉ trả cho admin; nhân viên nhận {@code null}
 */
public record PeriodView(
        String id,
        String name,
        String cycle,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime openAt,
        LocalDateTime deadline,
        String timezone,
        boolean autoLock,
        boolean manualClose,
        boolean allowReopenByAdmin,
        String status,
        String registrationState,
        String statusLabel,
        boolean registrationOpen,
        List<Integer> requiredDaysOfWeek,
        List<LocalDate> exemptDates,
        List<LocalDate> closedDates,
        List<String> applicableUserIds,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PeriodView of(PeriodRecord p, Instant now, ZoneId zone, boolean forAdmin) {
        RegistrationWindow.State state = RegistrationWindow.evaluate(p, now);
        return new PeriodView(
                p.id(), p.name(), p.cycle(), p.startDate(), p.endDate(),
                BusinessTime.toLocal(p.openAtUtc(), zone),
                BusinessTime.toLocal(p.deadlineUtc(), zone),
                zone.getId(),
                p.autoLock(), !p.autoLock(), p.allowReopenByAdmin(),
                p.status().dbValue(),
                state.name().toLowerCase(Locale.ROOT),
                RegistrationWindow.label(p, state, zone),
                state == RegistrationWindow.State.OPEN,
                p.requiredDaysOfWeek(), p.exemptDates(), p.closedDates(),
                forAdmin ? p.applicableUserIds() : null,
                p.version(),
                BusinessTime.toLocal(p.createdAtUtc(), zone),
                BusinessTime.toLocal(p.updatedAtUtc(), zone));
    }
}
