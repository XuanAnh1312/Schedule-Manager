package org.dacnpm.schedulemanager.api.leave;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Số dư phép của một nhân viên cho một loại nghỉ trong một năm (BAO-03).
 * {@code remaining = entitled + adjusted - used - pending}.
 */
public record LeaveBalanceView(
        String id,
        String userId,
        String userName,
        int year,
        String leaveTypeId,
        String leaveTypeName,
        String policyId,
        BigDecimal entitled,
        BigDecimal adjusted,
        BigDecimal used,
        BigDecimal pending,
        BigDecimal remaining,
        int version,
        LocalDateTime updatedAt
) {}
