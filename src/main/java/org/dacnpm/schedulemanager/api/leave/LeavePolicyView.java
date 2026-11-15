package org.dacnpm.schedulemanager.api.leave;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Chính sách phép năm: loại nghỉ × loại nhân viên (null = mọi loại) × ngày hiệu lực (BAO-03). */
public record LeavePolicyView(
        String id,
        String leaveTypeId,
        String leaveTypeName,
        String employeeTypeId,
        String employeeTypeName,
        BigDecimal daysPerYear,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String note,
        int version
) {
    public boolean coversDate(LocalDate day) {
        return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }
}
