package org.dacnpm.schedulemanager.api.availability;

import org.dacnpm.schedulemanager.api.periods.PeriodView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** Request/response của API đăng ký thời gian rảnh (TU-04). */
public final class AvailabilityModels {

    private AvailabilityModels() {}

    public static final String TYPE_AVAILABLE = "available";
    public static final String TYPE_UNAVAILABLE = "unavailable";

    /**
     * Một khoảng giờ trong ngày. {@code "00:00"–"23:59"} là cả ngày.
     *
     * @param type available (rảnh) | unavailable (không thể làm); mặc định available
     */
    public record Slot(LocalTime start, LocalTime end, String type) {}

    /** Một ngày trong kỳ với các khoảng giờ. Ngày không có khoảng nào bị bỏ qua khi lưu. */
    public record Entry(LocalDate date, List<Slot> slots) {}

    /** Body lưu nháp / gửi đăng ký: toàn bộ các ngày đang có trên form (thay thế bản cũ). */
    public record SaveRequest(List<Entry> entries) {}

    /**
     * Đăng ký của một nhân viên.
     *
     * @param status none (chưa đăng ký) | draft | submitted
     */
    public record Registration(
            String id,
            String periodId,
            String userId,
            String status,
            LocalDateTime submittedAt,
            LocalDateTime updatedAt,
            List<Entry> entries) {}

    /**
     * Màn hình đăng ký của nhân viên. {@code editable} được tính lại theo giờ server ở mỗi lần đọc.
     */
    public record MyAvailability(PeriodView period, Registration registration, boolean editable, String statusLabel) {}

    /** Tình trạng gửi đăng ký của một nhân viên trong kỳ. */
    public record EmployeeProgress(String userId, String employeeCode, String fullName, String status,
                                   LocalDateTime submittedAt) {}

    public record Progress(String periodId, int applicable, int submitted, int draft, int notStarted,
                           List<EmployeeProgress> employees) {}

    /**
     * Khả năng đáp ứng của một ngày, chỉ tính đăng ký đã gửi.
     *
     * @param registrationRequired ngày thuộc thứ cần đăng ký, không miễn, không đóng
     * @param availableHours        tổng giờ rảnh của mọi nhân viên trong ngày
     */
    public record DaySummary(LocalDate date, int dayOfWeek, boolean registrationRequired, boolean exempt,
                             boolean closed, int availableEmployees, int unavailableEmployees,
                             BigDecimal availableHours) {}

    public record Summary(String periodId, int applicable, int submitted, List<DaySummary> days) {}
}
