package org.dacnpm.schedulemanager.schedule.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Dữ liệu báo cáo lịch cho module báo cáo/Dashboard (Bình). Mọi phản hồi có {@link ReportContext}: cùng ngữ cảnh thì
 * cùng số liệu với màn hình phân lịch. Trạng thái workflow và độ phủ là hai trường riêng, không bao giờ gộp.
 */
public final class ReportModels {

    private ReportModels() {
    }

    public static final String SOURCE_PUBLISHED = "published";
    public static final String SOURCE_PLAN = "plan";

    /**
     * Ngữ cảnh chuẩn của một báo cáo.
     *
     * @param source        published: chỉ ca đã công bố và phân công chính thức confirmed; plan: roster của đúng một
     *                      phương án. Hai nguồn không bao giờ bị cộng chung.
     * @param planId        phương án khi source = plan
     * @param planStatus    trạng thái workflow của phương án (draft, pending_approval, approved, published, superseded,
     *                      cancelled)
     * @param planVersion   version của phương án tại thời điểm báo cáo
     * @param hoursBasis    cách tính giờ
     * @param rounding      quy tắc làm tròn số giờ
     * @param rulesSource   nơi lấy giờ mục tiêu/giới hạn
     */
    public record ReportContext(String periodId, String periodName, String periodStatus, LocalDate from, LocalDate to,
                                String source, String planId, String planStatus, Integer planVersion,
                                Map<String, String> filters, Instant generatedAt, String hoursBasis, String rounding,
                                String rulesSource) {
    }

    /** Độ phủ của một tập ca: đủ, dưới tổng nhu cầu nhưng đạt tối thiểu, thiếu người. */
    public record Coverage(int totalShifts, int full, int belowTarget, int understaffed, int requiredPositions,
                           int filledPositions) {
    }

    /** Một phiên bản/phương án của kỳ: trạng thái workflow tách khỏi độ phủ. */
    public record PlanVersion(String planId, String name, String workflowStatus, int version, int runCount,
                              LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime publishedAt,
                              String supersededBy, int assignments, double totalHours, Coverage coverage) {
    }

    public record VersionsReport(ReportContext context, Coverage publishedCoverage, List<PlanVersion> versions) {
    }

    /**
     * Giờ của một nhân viên trong kỳ theo nguồn của ngữ cảnh.
     *
     * @param peakWeekHours     số giờ của tuần cao nhất
     * @param maxHoursPerWeek   trần tuần có hiệu lực
     * @param percentOfTarget   hours / targetHours × 100; null nếu không có mục tiêu
     * @param limitStatus       ok, near_limit (≥ 90% trần tuần hoặc kỳ), at_limit, over_limit
     */
    public record EmployeeHours(String userId, String fullName, String levelId, int shifts, double hours,
                                Double targetHours, Double percentOfTarget, double peakWeekHours,
                                double maxHoursPerWeek, Double maxHoursPerPeriod, String maxHoursFormula,
                                String limitStatus, Map<LocalDate, Double> hoursByWeek) {
    }

    public record HoursTotals(int employees, int shifts, double hours, double targetHours, int overLimit, int nearLimit,
                              int belowTarget) {
    }

    public record HoursReport(ReportContext context, HoursTotals totals, List<EmployeeHours> employees) {
    }

    /** Một phân công (dòng phẳng để xuất CSV). assignmentId chỉ có với nguồn published. */
    public record AssignmentLine(String shiftId, String shiftName, LocalDate date, LocalTime startTime,
                                 LocalTime endTime, int breakMinutes, String locationId, String userId, String fullName,
                                 String levelId, double hoursInPeriod, String workflowStatus, String coverage,
                                 String assignmentId) {
    }

    public record AssignmentsReport(ReportContext context, int count, double totalHours, List<AssignmentLine> lines) {
    }

    /**
     * Độ phủ của một ca. reasons: lý do thiếu người từ lần chạy phương án (chỉ với nguồn plan).
     */
    public record ShiftLine(String shiftId, String shiftName, LocalDate date, LocalTime startTime, LocalTime endTime,
                            String locationId, String workflowStatus, String coverage, int required, int minimum,
                            int filled, int missing, List<String> reasons) {
    }

    public record ShiftsReport(ReportContext context, Coverage coverage, List<ShiftLine> shifts) {
    }
}
