package org.dacnpm.schedulemanager.schedule.rules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.dacnpm.schedulemanager.api.rules.RuleService;
import org.dacnpm.schedulemanager.api.rules.RuleModels;

/**
 * Nguồn quy tắc mặc định: một bộ schedule_rules cho cả công ty, không đổi theo ngày; giới hạn riêng của nhân viên suy
 * ra từ target_rate và giới hạn hợp đồng ({@link EmployeeRules#derive}).
 */
@Component
@Profile("sqlserver")
public class TableRulesSource implements EffectiveRulesSource {

    private final RuleService service;

    public TableRulesSource(RuleService service) { this.service = service; }

    @Override
    public EffectiveRules load(Period period, ScheduleRules base, List<Employee> employees) {
        Map<String, Employee> byId = new HashMap<>();
        employees.forEach(e -> byId.put(e.id(), e));
        Map<String, EmployeeRules> cache = new HashMap<>();
        return (userId, date) -> cache.computeIfAbsent(userId + ":" + date,
                ignored -> convert(service.effectiveForScheduling(userId, date), byId.get(userId)));
    }

    static EmployeeRules convert(RuleModels.EffectiveRules rules, Employee employee) {
        Map<String, String> values = new HashMap<>();
        rules.values().forEach(v -> values.put(v.key(), v.value()));
        List<EmployeeRules.MissingParameter> missing = new java.util.ArrayList<>();
        rules.missing().forEach(m -> missing.add(new EmployeeRules.MissingParameter(m.key(), m.label(), m.reason())));
        rules.issues().forEach(issue -> missing.add(new EmployeeRules.MissingParameter("rules", "Quy tắc", issue)));
        double week = number(values, "max_hours_per_week", 0);
        if (employee != null && employee.contractMaxHoursPerWeek() != null) {
            week = Math.min(week, employee.contractMaxHoursPerWeek());
        }
        return new EmployeeRules(number(values, "max_hours_per_day", 0), week,
                rules.derived().maxHours() == null ? null : rules.derived().maxHours().doubleValue(),
                number(values, "min_rest_hours", 0), integer(values, "max_consecutive_days"),
                rules.derived().targetHours() == null ? null : rules.derived().targetHours().doubleValue(),
                rules.derived().maxHoursFormula(), integer(values, "weight_balance_hours"),
                integer(values, "weight_balance_shifts"), missing,
                rules.derived().warningHours()==null?null:rules.derived().warningHours().doubleValue());
    }

    private static double number(Map<String, String> values, String key, double fallback) {
        return values.containsKey(key) ? Double.parseDouble(values.get(key)) : fallback;
    }

    private static Integer integer(Map<String, String> values, String key) {
        return values.containsKey(key) ? Integer.valueOf(values.get(key)) : null;
    }
}
