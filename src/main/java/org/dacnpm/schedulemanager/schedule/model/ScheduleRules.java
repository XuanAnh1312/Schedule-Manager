package org.dacnpm.schedulemanager.schedule.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Quy tắc phân lịch (bảng schedule_rules).
 *
 * @param targetHoursPerWeek  giờ mục tiêu/tuần; trần giờ tuần của nhân viên = giá trị này × target_rate / 100.
 *                            null nghĩa là không áp dụng trần theo target_rate
 * @param maxConsecutiveDays  số ngày làm liên tiếp tối đa; null là không giới hạn
 * @param substitutions       Level cao -> các Level thấp hơn mà nó được phép thay
 */
public record ScheduleRules(
        double maxHoursPerDay,
        double maxHoursPerWeek,
        double maxHoursPerPeriod,
        double minRestBetweenShiftsHours,
        Double targetHoursPerWeek,
        Integer maxConsecutiveDays,
        Map<String, Set<String>> substitutions) {

    public ScheduleRules {
        Map<String, Set<String>> copy = new HashMap<>();
        if (substitutions != null) {
            substitutions.forEach((higher, lowers) -> copy.put(higher, lowers == null ? Set.of() : Set.copyOf(lowers)));
        }
        substitutions = Map.copyOf(copy);
    }

    /** Level của nhân viên đáp ứng vị trí khi trùng Level yêu cầu hoặc được phép thay Level đó. */
    public boolean levelSatisfies(String employeeLevelId, String requiredLevelId) {
        if (employeeLevelId == null || requiredLevelId == null) {
            return false;
        }
        return employeeLevelId.equals(requiredLevelId)
                || substitutions.getOrDefault(employeeLevelId, Set.of()).contains(requiredLevelId);
    }
}
