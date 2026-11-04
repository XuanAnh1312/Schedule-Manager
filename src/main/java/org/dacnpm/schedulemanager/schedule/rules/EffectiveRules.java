package org.dacnpm.schedulemanager.schedule.rules;

import java.time.LocalDate;

/** Tra cứu quy tắc có hiệu lực của một nhân viên tại một ngày, đã được nạp sẵn cho một kỳ. */
@FunctionalInterface
public interface EffectiveRules {

    /**
     * @return quy tắc hiệu lực; {@link TableRulesSource} luôn trả giá trị (thiếu cấu hình nằm trong missing). null chỉ
     *         dùng cho ngữ cảnh test không nối module quy tắc, khi đó {@code ScheduleContext} suy ra từ ScheduleRules
     */
    EmployeeRules at(String userId, LocalDate date);
}
