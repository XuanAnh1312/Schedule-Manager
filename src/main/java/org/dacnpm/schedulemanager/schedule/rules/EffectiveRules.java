package org.dacnpm.schedulemanager.schedule.rules;

import java.time.LocalDate;

/** Tra cứu quy tắc có hiệu lực của một nhân viên tại một ngày, đã được nạp sẵn cho một kỳ. */
@FunctionalInterface
public interface EffectiveRules {

    /**
     * @return quy tắc hiệu lực, hoặc null nếu nguồn không có dữ liệu cho nhân viên này (khi đó dùng quy tắc suy ra từ
     *         schedule_rules)
     */
    EmployeeRules at(String userId, LocalDate date);
}
