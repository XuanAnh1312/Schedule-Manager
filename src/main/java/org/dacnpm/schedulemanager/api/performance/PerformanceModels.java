package org.dacnpm.schedulemanager.api.performance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Dữ liệu module hiệu suất/KPI (BAO-06). */
public final class PerformanceModels {

    private PerformanceModels() {}

    public static final String MANUAL = "manual";
    public static final String HOURS_VS_SCHEDULE = "hours_vs_schedule";
    public static final String ATTENDANCE_RATE = "attendance_rate";
    public static final String ABSENCE_RATE = "absence_rate";
    public static final String HIGHER_BETTER = "higher_better";
    public static final String LOWER_BETTER = "lower_better";

    public record Kpi(String id, String name, String description, String unit, String formulaType, String direction,
                      BigDecimal targetValue, BigDecimal weight, String departmentId, boolean active, int version) {}

    public record Cycle(String id, String name, LocalDate periodStart, LocalDate periodEnd, int scoreScaleMax,
                        String status, int version, long reviews, long completed) {}

    public record Review(String id, String cycleId, String cycleName, LocalDate periodStart, LocalDate periodEnd,
                         int scoreScaleMax, String employeeId, String employeeName, String reviewerId,
                         String reviewerName, String status, String selfComment, String managerComment,
                         String improvementPlan, String employeeFeedback, BigDecimal overallScore,
                         LocalDateTime selfSubmittedAt, LocalDateTime managerSubmittedAt, LocalDateTime acknowledgedAt,
                         int version, List<Item> items) {
        public Review withItems(List<Item> list) {
            return new Review(id, cycleId, cycleName, periodStart, periodEnd, scoreScaleMax, employeeId, employeeName,
                    reviewerId, reviewerName, status, selfComment, managerComment, improvementPlan, employeeFeedback,
                    overallScore, selfSubmittedAt, managerSubmittedAt, acknowledgedAt, version, list);
        }
    }

    public record Item(String id, String reviewId, String kpiId, String kpiName, String unit, String formulaType,
                       String direction, BigDecimal targetValue, BigDecimal weight, BigDecimal actualValue,
                       String actualBasis, BigDecimal selfScore, BigDecimal managerScore, String comment) {}

    public record Achievement(String id, String employeeId, String employeeName, String kind, String title,
                              String description, BigDecimal suggestedAmount, String reviewId, String createdBy,
                              String createdByName, LocalDateTime createdAt) {}

    /** Số liệu thô của một người trong khoảng ngày để tự tính KPI. */
    public record Metrics(long assignments, long scheduledMinutes, long attended, long workedMinutes, long absences) {}

    /** Kết quả tự tính: {@code value} null = chưa đủ dữ liệu (mẫu số 0), không hiển thị số giả. */
    public record Computed(BigDecimal value, String basis) {}

    /* ── Đầu vào API ───────────────────────────────────────────────────── */

    public record KpiInput(String name, String description, String unit, String formulaType, String direction,
                           BigDecimal targetValue, BigDecimal weight, String departmentId, Boolean active,
                           Integer expectedVersion) {}

    public record CycleInput(String name, LocalDate periodStart, LocalDate periodEnd, Integer scoreScaleMax) {}

    public record ScoreInput(String itemId, BigDecimal score, BigDecimal actualValue, String comment) {}

    public record SelfReviewInput(List<ScoreInput> items, String comment, Integer expectedVersion) {}

    public record ManagerReviewInput(List<ScoreInput> items, String comment, String improvementPlan,
                                     Integer expectedVersion) {}

    public record AcknowledgeInput(String feedback, Integer expectedVersion) {}

    public record AchievementInput(String employeeId, String kind, String title, String description,
                                   BigDecimal suggestedAmount, String reviewId) {}
}
