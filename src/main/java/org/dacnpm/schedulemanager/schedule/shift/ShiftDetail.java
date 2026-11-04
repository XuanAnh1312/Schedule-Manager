package org.dacnpm.schedulemanager.schedule.shift;

import java.time.Instant;

/**
 * Ca đã lưu. status là trạng thái hiện có của cột shifts.status (chưa tách workflow/độ phủ, thuộc TW-07).
 * Ca đã hủy có cancelledAt khác null.
 */
public record ShiftDetail(
        String id,
        ShiftRequest shift,
        String status,
        int version,
        boolean emergency,
        String seriesId,
        Instant cancelledAt,
        String cancelledBy,
        String cancelReason) {

    public boolean cancelled() {
        return cancelledAt != null;
    }
}
