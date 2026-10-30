package org.dacnpm.schedulemanager.schedule.rules;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;

/**
 * Quy tắc có hiệu lực của một nhân viên tại một ngày, dùng cho kiểm tra (ràng buộc cứng) và chấm điểm (tiêu chí mềm).
 * Mô hình khớp với {@code RuleService.effectiveForScheduling(userId, date)} của module quy tắc: giờ mục tiêu là tiêu
 * chí mềm, giờ tối đa là ràng buộc cứng, thiếu tham số bắt buộc thì có trong {@link #missing} thay vì tự điền.
 *
 * @param maxHoursPerDay      giới hạn giờ mỗi ngày (cứng)
 * @param maxHoursPerWeek     giới hạn giờ mỗi tuần thứ hai–chủ nhật (cứng)
 * @param maxHoursPerPeriod   giới hạn giờ trong kỳ (cứng); null nếu không áp dụng
 * @param minRestHours        nghỉ tối thiểu giữa hai ca (cứng)
 * @param maxConsecutiveDays  số ngày làm liên tiếp tối đa (cứng); null là không giới hạn
 * @param targetHours         giờ mục tiêu trong kỳ (mềm, dùng để cân bằng); null nếu không tính được
 * @param maxHoursFormula     cách suy ra giới hạn chặt nhất, ví dụ "60 × 125% = 75 giờ/tuần"; null nếu không có
 * @param weightBalanceHours  trọng số 0–100 đưa giờ về gần mục tiêu; null là chưa cấu hình
 * @param weightBalanceShifts trọng số 0–100 chia đều số ca; null là chưa cấu hình
 * @param missing             tham số bắt buộc còn thiếu; khác rỗng thì không được phân lịch nhân viên này
 */
public record EmployeeRules(
        double maxHoursPerDay,
        double maxHoursPerWeek,
        Double maxHoursPerPeriod,
        double minRestHours,
        Integer maxConsecutiveDays,
        Double targetHours,
        String maxHoursFormula,
        Integer weightBalanceHours,
        Integer weightBalanceShifts,
        List<MissingParameter> missing) {

    /** Tham số bắt buộc còn thiếu: mã tham số, tên hiển thị và lý do. */
    public record MissingParameter(String key, String label, String reason) {
    }

    public EmployeeRules {
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    public boolean ready() {
        return missing.isEmpty();
    }

    /**
     * Suy ra quy tắc của nhân viên từ bảng schedule_rules và thông số của nhân viên (nguồn mặc định khi chưa có module
     * quy tắc hiệu lực):
     * <ul>
     *   <li>trần tuần = nhỏ nhất của giới hạn chính sách, giới hạn hợp đồng và giờ mục tiêu tuần × target_rate / 100;</li>
     *   <li>giờ mục tiêu kỳ = standard_hours × target_rate / 100, hoặc giờ mục tiêu tuần × target_rate / 100 × số tuần
     *       của kỳ khi không có standard_hours;</li>
     *   <li>most_needed ứng với trọng số cân bằng giờ, least_hours ứng với trọng số cân bằng số ca.</li>
     * </ul>
     */
    public static EmployeeRules derive(ScheduleRules rules, Employee employee, Period period) {
        List<MissingParameter> missing = new ArrayList<>();
        Double rate = employee.targetRate();
        if (rate == null && rules.targetHoursPerWeek() != null) {
            missing.add(new MissingParameter("target_rate", "Tỷ lệ giờ mục tiêu",
                    "Chưa có target_rate nên không tính được trần giờ/tuần."));
        }

        double week = rules.maxHoursPerWeek();
        String formula = null;
        if (employee.contractMaxHoursPerWeek() != null && employee.contractMaxHoursPerWeek() < week) {
            week = employee.contractMaxHoursPerWeek();
            formula = "hợp đồng " + plain(week) + " giờ/tuần";
        }
        if (rules.targetHoursPerWeek() != null && rate != null) {
            double byTarget = rules.targetHoursPerWeek() * rate / 100.0;
            if (byTarget < week) {
                week = byTarget;
                formula = plain(rules.targetHoursPerWeek()) + " × " + plain(rate) + "% = " + plain(byTarget) + " giờ/tuần";
            }
        }
        if (formula == null) {
            formula = "chính sách " + plain(week) + " giờ/tuần";
        }

        Double target = null;
        if (rate != null && rules.standardHours() != null) {
            target = rules.standardHours() * rate / 100.0;
        } else if (rate != null && rules.targetHoursPerWeek() != null && period != null) {
            long days = ChronoUnit.DAYS.between(period.startDate(), period.endDate()) + 1;
            target = rules.targetHoursPerWeek() * rate / 100.0 * days / 7.0;
        }

        boolean leastHours = ScheduleRules.LEAST_HOURS.equals(rules.schedulingPriority());
        return new EmployeeRules(rules.maxHoursPerDay(), week, rules.maxHoursPerPeriod(),
                rules.minRestBetweenShiftsHours(), rules.maxConsecutiveDays(), target, formula,
                leastHours ? 0 : 100, leastHours ? 100 : 0, missing);
    }

    static String plain(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }
}
