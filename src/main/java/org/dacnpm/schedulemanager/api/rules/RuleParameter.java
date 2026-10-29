package org.dacnpm.schedulemanager.api.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Danh mục tham số Admin cấu hình được (TU-05). Không có giá trị mặc định nghiệp vụ nào ở đây:
 * tham số bắt buộc chưa cấu hình sẽ xuất hiện trong checklist thay vì tự điền (TU-06).
 *
 * <p>{@link Category} sắp theo thứ tự ưu tiên khi xung đột: ràng buộc pháp luật/an toàn →
 * hợp đồng/chứng chỉ → quy tắc doanh nghiệp → tiêu chí ưu tiên của thuật toán → hiển thị.
 * Hai nhóm đầu chưa có dữ liệu nguồn trong V1 (hợp đồng/chứng chỉ thuộc HR-02, LS-03).
 */
public enum RuleParameter {

    TARGET_HOURS("target_hours", "Giờ mục tiêu trong kỳ", "giờ",
            "Số giờ hệ thống cố gắng phân cho mỗi nhân viên trong một kỳ. Dùng để cân bằng, không phải giới hạn.",
            ValueType.DECIMAL, "0.5", "744", Kind.SOFT, Category.ALGORITHM_PREFERENCE, true, false, false),

    MAX_HOURS_MODE("max_hours_mode", "Cách đặt giờ tối đa", null,
            "fixed = nhập số giờ tối đa cụ thể; percent = nhập tỷ lệ % so với giờ mục tiêu, hệ thống tự tính.",
            ValueType.ENUM, null, null, Kind.HARD, Category.BUSINESS_RULE, true, false, false, "fixed", "percent"),

    MAX_HOURS_FIXED("max_hours_fixed", "Giờ tối đa trong kỳ (cố định)", "giờ",
            "Trần giờ tự động phân trong kỳ khi cách đặt là fixed. Thuật toán không phân vượt giá trị này.",
            ValueType.DECIMAL, "0.5", "744", Kind.HARD, Category.BUSINESS_RULE, false, false, false),

    MAX_HOURS_PERCENT("max_hours_percent", "Giờ tối đa theo % mục tiêu", "%",
            "Khi cách đặt là percent: giờ tối đa = giờ mục tiêu × tỷ lệ / 100. Ví dụ 60 giờ × 125% = 75 giờ.",
            ValueType.DECIMAL, "1", "300", Kind.HARD, Category.BUSINESS_RULE, false, false, false),

    WARNING_THRESHOLD_PERCENT("warning_threshold_percent", "Ngưỡng cảnh báo gần giới hạn", "% giờ tối đa",
            "Cảnh báo khi giờ đã xếp đạt tỷ lệ này của giờ tối đa. Bỏ trống = không cảnh báo sớm.",
            ValueType.DECIMAL, "1", "100", Kind.SOFT, Category.BUSINESS_RULE, false, false, false),

    MAX_HOURS_PER_DAY("max_hours_per_day", "Giờ tối đa mỗi ngày", "giờ",
            "Tổng giờ các ca của một nhân viên trong một ngày không vượt giá trị này.",
            ValueType.DECIMAL, "0.5", "24", Kind.HARD, Category.BUSINESS_RULE, true, false, false),

    MAX_HOURS_PER_WEEK("max_hours_per_week", "Giờ tối đa mỗi tuần", "giờ",
            "Tổng giờ các ca trong một tuần (Thứ hai – Chủ nhật) không vượt giá trị này.",
            ValueType.DECIMAL, "0.5", "168", Kind.HARD, Category.BUSINESS_RULE, true, false, false),

    MIN_REST_HOURS("min_rest_hours", "Nghỉ tối thiểu giữa hai ca", "giờ",
            "Khoảng nghỉ tối thiểu từ lúc kết thúc ca trước tới lúc bắt đầu ca sau của cùng nhân viên.",
            ValueType.DECIMAL, "0", "24", Kind.HARD, Category.BUSINESS_RULE, true, false, false),

    MAX_CONSECUTIVE_DAYS("max_consecutive_days", "Số ngày làm liên tiếp tối đa", "ngày",
            "Số ngày liên tiếp tối đa một nhân viên được phân ca.",
            ValueType.INTEGER, "1", "31", Kind.HARD, Category.BUSINESS_RULE, true, false, false),

    WEIGHT_BALANCE_HOURS("weight_balance_hours", "Trọng số: đưa giờ về gần mục tiêu", "điểm (0–100)",
            "Mức ưu tiên khi chọn người: càng cao càng ưu tiên người còn thiếu nhiều giờ so với mục tiêu.",
            ValueType.INTEGER, "0", "100", Kind.SOFT, Category.ALGORITHM_PREFERENCE, false, true, false),

    WEIGHT_BALANCE_SHIFTS("weight_balance_shifts", "Trọng số: cân bằng số ca", "điểm (0–100)",
            "Mức ưu tiên chia đều số ca giữa các nhân viên phù hợp.",
            ValueType.INTEGER, "0", "100", Kind.SOFT, Category.ALGORITHM_PREFERENCE, false, true, false),

    WEIGHT_AVAILABILITY_PREFERENCE("weight_availability_preference", "Trọng số: tôn trọng thời gian rảnh", "điểm (0–100)",
            "Mức ưu tiên xếp đúng khung giờ nhân viên đăng ký rảnh.",
            ValueType.INTEGER, "0", "100", Kind.SOFT, Category.ALGORITHM_PREFERENCE, false, true, false),

    WEIGHT_COST("weight_cost", "Trọng số: hạn chế chi phí", "điểm (0–100)",
            "Mức ưu tiên giảm tăng ca/chi phí khi có dữ liệu đơn giá (mốc B).",
            ValueType.INTEGER, "0", "100", Kind.SOFT, Category.ALGORITHM_PREFERENCE, false, true, false),

    REPORT_ROUNDING_DECIMALS("report_rounding_decimals", "Số chữ số thập phân khi hiển thị báo cáo", "chữ số",
            "Chỉ đổi cách hiển thị (ví dụ 109% hay 109.19%), không đổi công thức tính tỷ lệ.",
            ValueType.INTEGER, "0", "2", Kind.DISPLAY, Category.DISPLAY, false, true, false),

    LEVEL_SUBSTITUTIONS("level_substitutions", "Level được phép thay thế", null,
            "Danh sách cặp {higherLevelId, lowerLevelId}: Level cao được làm thay vị trí cần Level thấp. "
                    + "Áp dụng toàn công ty, có hiệu lực ngay khi xác nhận.",
            ValueType.LEVEL_PAIRS, null, null, Kind.HARD, Category.BUSINESS_RULE, false, true, true);

    public enum ValueType { DECIMAL, INTEGER, ENUM, LEVEL_PAIRS }

    /** HARD = điều kiện chặn; SOFT = tiêu chí ưu tiên; DISPLAY = chỉ hiển thị. */
    public enum Kind { HARD, SOFT, DISPLAY }

    /** Thứ tự ưu tiên khi xung đột (số nhỏ thắng). */
    public enum Category {
        LEGAL_SAFETY(1), CONTRACT_CERTIFICATE(2), BUSINESS_RULE(3), ALGORITHM_PREFERENCE(4), DISPLAY(5);

        private final int priority;

        Category(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    private final String key;
    private final String label;
    private final String unit;
    private final String description;
    private final ValueType type;
    private final BigDecimal min;
    private final BigDecimal max;
    private final Kind kind;
    private final Category category;
    private final boolean requiredForScheduling;
    private final boolean companyOnly;
    private final boolean immediateOnly;
    private final List<String> allowedValues;

    RuleParameter(String key, String label, String unit, String description, ValueType type, String min, String max,
                  Kind kind, Category category, boolean requiredForScheduling, boolean companyOnly,
                  boolean immediateOnly, String... allowedValues) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.description = description;
        this.type = type;
        this.min = min == null ? null : new BigDecimal(min);
        this.max = max == null ? null : new BigDecimal(max);
        this.kind = kind;
        this.category = category;
        this.requiredForScheduling = requiredForScheduling;
        this.companyOnly = companyOnly;
        this.immediateOnly = immediateOnly;
        this.allowedValues = List.of(allowedValues);
    }

    public String key() { return key; }
    public String label() { return label; }
    public String unit() { return unit; }
    public String description() { return description; }
    public ValueType type() { return type; }
    public BigDecimal min() { return min; }
    public BigDecimal max() { return max; }
    public Kind kind() { return kind; }
    public Category category() { return category; }
    /** Bắt buộc vô điều kiện; max_hours_fixed/percent bắt buộc theo max_hours_mode. */
    public boolean requiredForScheduling() { return requiredForScheduling; }
    public boolean companyOnly() { return companyOnly; }
    /** Chỉ có hiệu lực ngay (không hẹn ngày), vì bảng runtime dùng chung không có cột ngày hiệu lực. */
    public boolean immediateOnly() { return immediateOnly; }
    public List<String> allowedValues() { return allowedValues; }

    public static Optional<RuleParameter> byKey(String key) {
        for (RuleParameter p : values()) {
            if (p.key.equals(key)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
