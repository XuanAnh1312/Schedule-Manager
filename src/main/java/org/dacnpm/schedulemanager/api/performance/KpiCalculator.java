package org.dacnpm.schedulemanager.api.performance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.dacnpm.schedulemanager.api.performance.PerformanceModels.*;

/**
 * Tự tính KPI từ lịch/chấm công (BAO-06). Mọi KPI theo giờ đều <b>chuẩn hóa theo lịch của chính người đó</b>
 * (giờ thực tế / giờ được phân), nên nhân viên Part-time làm đủ lịch của mình đạt 100% như Full-time — không bị điểm
 * thấp chỉ vì ít giờ. Mẫu số 0 → giá trị null ("chưa đủ dữ liệu").
 */
public final class KpiCalculator {

    private KpiCalculator() {}

    public static Computed compute(String formulaType, Metrics m) {
        return switch (formulaType) {
            case HOURS_VS_SCHEDULE -> percent(m.workedMinutes(), m.scheduledMinutes(),
                    hours(m.workedMinutes()) + " giờ chấm công / " + hours(m.scheduledMinutes()) + " giờ được phân");
            case ATTENDANCE_RATE -> percent(m.attended(), m.assignments(),
                    m.attended() + " ca có chấm công / " + m.assignments() + " ca được phân");
            case ABSENCE_RATE -> percent(m.absences(), m.assignments(),
                    m.absences() + " lần vắng đột xuất / " + m.assignments() + " ca được phân");
            default -> new Computed(null, "Quản lý nhập kết quả");
        };
    }

    /** Điểm có trọng số trên thang của kỳ; mục chưa chấm bị bỏ qua. Không có mục nào → null. */
    public static BigDecimal weighted(List<Item> items) {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal weights = BigDecimal.ZERO;
        for (Item i : items) {
            if (i.managerScore() == null) continue;
            sum = sum.add(i.managerScore().multiply(i.weight()));
            weights = weights.add(i.weight());
        }
        return weights.signum() == 0 ? null : sum.divide(weights, 2, RoundingMode.HALF_UP);
    }

    private static Computed percent(long numerator, long denominator, String basis) {
        if (denominator <= 0) return new Computed(null, basis + " — chưa đủ dữ liệu");
        BigDecimal v = BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(2, RoundingMode.HALF_UP);
        return new Computed(v, basis);
    }

    private static String hours(long minutes) {
        return BigDecimal.valueOf(minutes / 60.0).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
