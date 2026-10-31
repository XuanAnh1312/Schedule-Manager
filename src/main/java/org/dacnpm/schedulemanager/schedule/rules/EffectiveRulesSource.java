package org.dacnpm.schedulemanager.schedule.rules;

import java.util.List;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;

/**
 * Điểm nối với module quy tắc (TU-06). Bản mặc định {@link TableRulesSource} suy ra từ bảng schedule_rules. Khi module
 * quy tắc hiệu lực được gộp vào, thêm một bean {@code @Primary} cài interface này bằng cách gọi
 * {@code RuleService.effectiveForScheduling(userId, date)} và ánh xạ sang {@link EmployeeRules}
 * (max_hours_per_day, max_hours_per_week, giờ tối đa đã suy ra, min_rest_hours, max_consecutive_days, target_hours,
 * weight_balance_hours, weight_balance_shifts, missing). Engine và bộ kiểm tra không cần sửa.
 */
public interface EffectiveRulesSource {

    EffectiveRules load(Period period, ScheduleRules base, List<Employee> employees);
}
