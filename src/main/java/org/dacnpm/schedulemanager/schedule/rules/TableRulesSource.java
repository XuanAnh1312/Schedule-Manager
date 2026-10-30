package org.dacnpm.schedulemanager.schedule.rules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.springframework.stereotype.Component;

/**
 * Nguồn quy tắc mặc định: một bộ schedule_rules cho cả công ty, không đổi theo ngày; giới hạn riêng của nhân viên suy
 * ra từ target_rate và giới hạn hợp đồng ({@link EmployeeRules#derive}).
 */
@Component
public class TableRulesSource implements EffectiveRulesSource {

    @Override
    public EffectiveRules load(Period period, ScheduleRules base, List<Employee> employees) {
        Map<String, EmployeeRules> byUser = new HashMap<>();
        for (Employee employee : employees) {
            byUser.put(employee.id(), EmployeeRules.derive(base, employee, period));
        }
        return (userId, date) -> byUser.get(userId);
    }
}
