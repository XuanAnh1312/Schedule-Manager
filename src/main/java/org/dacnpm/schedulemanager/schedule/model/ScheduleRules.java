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
 * @param levelOrder          levelId -> thứ tự (levels.sort_order, số lớn là Level cao); rỗng nếu không biết
 */
public record ScheduleRules(
        double maxHoursPerDay,
        double maxHoursPerWeek,
        double maxHoursPerPeriod,
        double minRestBetweenShiftsHours,
        Double targetHoursPerWeek,
        Integer maxConsecutiveDays,
        Map<String, Set<String>> substitutions,
        Map<String, Integer> levelOrder) {

    /** Giữ tương thích với nơi gọi cũ chưa có thứ tự Level. */
    public ScheduleRules(double maxHoursPerDay, double maxHoursPerWeek, double maxHoursPerPeriod,
                         double minRestBetweenShiftsHours, Double targetHoursPerWeek, Integer maxConsecutiveDays,
                         Map<String, Set<String>> substitutions) {
        this(maxHoursPerDay, maxHoursPerWeek, maxHoursPerPeriod, minRestBetweenShiftsHours, targetHoursPerWeek,
                maxConsecutiveDays, substitutions, null);
    }

    public ScheduleRules {
        Map<String, Set<String>> copy = new HashMap<>();
        if (substitutions != null) {
            substitutions.forEach((higher, lowers) -> copy.put(higher, lowers == null ? Set.of() : Set.copyOf(lowers)));
        }
        substitutions = Map.copyOf(copy);
        levelOrder = levelOrder == null ? Map.of() : Map.copyOf(levelOrder);
    }

    /**
     * Level của nhân viên đáp ứng vị trí khi trùng Level yêu cầu, hoặc khi có quy tắc thay thế và Level của nhân viên
     * cao hơn Level yêu cầu. Quy tắc ghi ngược chiều (Level thấp thay Level cao) bị bỏ qua khi biết thứ tự Level.
     */
    public boolean levelSatisfies(String employeeLevelId, String requiredLevelId) {
        if (employeeLevelId == null || requiredLevelId == null) {
            return false;
        }
        if (employeeLevelId.equals(requiredLevelId)) {
            return true;
        }
        if (!substitutions.getOrDefault(employeeLevelId, Set.of()).contains(requiredLevelId)) {
            return false;
        }
        Integer employeeOrder = levelOrder.get(employeeLevelId);
        Integer requiredOrder = levelOrder.get(requiredLevelId);
        return employeeOrder == null || requiredOrder == null || employeeOrder > requiredOrder;
    }
}
