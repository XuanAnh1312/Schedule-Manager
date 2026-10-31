package org.dacnpm.schedulemanager.api.periods;

import org.dacnpm.schedulemanager.common.BusinessTime;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Quy tắc duy nhất quyết định nhân viên có được tạo/sửa/lưu nháp/gửi đăng ký hay không (TU-02).
 * Mọi API đăng ký gọi hàm này với giờ server ({@code Clock}), không tin giờ trình duyệt.
 *
 * <pre>
 * được đăng ký = status == open
 *             && (open_at rỗng hoặc now >= open_at)
 *             && (auto_lock tắt hoặc deadline rỗng hoặc now < deadline)
 * </pre>
 *
 * Tác vụ nền chỉ đổi status sang locked để màn hình hiển thị đúng; nếu tác vụ chạy trễ,
 * hàm này vẫn trả CLOSED ngay khi qua hạn.
 */
public final class RegistrationWindow {

    public enum State {
        /** Kỳ nháp: nhân viên không thấy. */
        HIDDEN,
        /** Đã công bố nhưng chưa tới open_at. */
        UPCOMING,
        OPEN,
        CLOSED,
        ARCHIVED
    }

    private RegistrationWindow() {}

    public static State evaluate(PeriodRecord period, Instant now) {
        return switch (period.status()) {
            case DRAFT -> State.HIDDEN;
            case LOCKED -> State.CLOSED;
            case ARCHIVED -> State.ARCHIVED;
            case OPEN -> evaluateOpen(period, now);
        };
    }

    private static State evaluateOpen(PeriodRecord period, Instant now) {
        Instant openAt = BusinessTime.instantOfUtc(period.openAtUtc());
        if (openAt != null && now.isBefore(openAt)) {
            return State.UPCOMING;
        }
        Instant deadline = BusinessTime.instantOfUtc(period.deadlineUtc());
        if (period.autoLock() && deadline != null && !now.isBefore(deadline)) {
            return State.CLOSED;
        }
        return State.OPEN;
    }

    /** Nhãn cho giao diện, ví dụ "Đã đóng đăng ký — hạn chót 05/10/2026 23:59". */
    public static String label(PeriodRecord period, State state, ZoneId zone) {
        String deadline = BusinessTime.label(period.deadlineUtc(), zone);
        return switch (state) {
            case HIDDEN -> "Nháp";
            case UPCOMING -> "Sắp mở đăng ký — mở lúc " + BusinessTime.label(period.openAtUtc(), zone);
            case OPEN -> openLabel(period, deadline);
            case CLOSED -> deadline == null ? "Đã đóng đăng ký" : "Đã đóng đăng ký — hạn chót " + deadline;
            case ARCHIVED -> "Đã lưu trữ";
        };
    }

    private static String openLabel(PeriodRecord period, String deadline) {
        if (!period.autoLock()) {
            return deadline == null
                    ? "Đang mở đăng ký — kỳ đóng thủ công"
                    : "Đang mở đăng ký — kỳ đóng thủ công (hạn tham khảo " + deadline + ")";
        }
        return deadline == null ? "Đang mở đăng ký" : "Đang mở đăng ký — hạn chót " + deadline;
    }
}
