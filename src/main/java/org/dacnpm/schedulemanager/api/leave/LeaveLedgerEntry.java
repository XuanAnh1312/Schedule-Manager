package org.dacnpm.schedulemanager.api.leave;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Một dòng sổ cái phép (BAO-03). {@code days} luôn dương; chiều tác động theo {@code entryType}. */
public record LeaveLedgerEntry(
        String id,
        String balanceId,
        String entryType,
        BigDecimal days,
        String leaveRequestId,
        String reason,
        String actorId,
        String actorName,
        LocalDateTime createdAt
) {}
