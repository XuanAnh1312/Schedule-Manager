package org.dacnpm.schedulemanager.schedule.rules;

import java.util.List;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;

/**
 * Điểm nối với module quy tắc (TU-06). Bản chạy thật là {@link TableRulesSource}: gọi
 * {@code RuleService.effectiveForScheduling(userId, date)} và ánh xạ sang {@link EmployeeRules}
 * (max_hours_per_day, max_hours_per_week, giờ tối đa đã suy ra, min_rest_hours, max_consecutive_days, target_hours,
 * weight_balance_hours, weight_balance_shifts, missing). Engine, validator và báo cáo đều đọc qua đây.
 */
public interface EffectiveRulesSource {

    EffectiveRules load(Period period, ScheduleRules base, List<Employee> employees);
}
