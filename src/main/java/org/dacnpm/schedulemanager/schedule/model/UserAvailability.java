package org.dacnpm.schedulemanager.schedule.model;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Đăng ký thời gian rảnh của nhân viên trong một kỳ.
 *
 * @param submitted   false nếu đăng ký còn là nháp (chưa gửi)
 * @param slotsByDate ngày -> các khoảng rảnh trong ngày đó
 */
public record UserAvailability(String userId, boolean submitted, Map<LocalDate, List<TimeRange>> slotsByDate) {

    public UserAvailability {
        Map<LocalDate, List<TimeRange>> copy = new HashMap<>();
        if (slotsByDate != null) {
            slotsByDate.forEach((date, slots) -> copy.put(date, slots == null ? List.of() : List.copyOf(slots)));
        }
        slotsByDate = Map.copyOf(copy);
    }
}
