package org.dacnpm.schedulemanager.schedule.validation;

/**
 * Một vi phạm ràng buộc.
 *
 * @param parameter thông số/ràng buộc liên quan (ví dụ max_hours_per_day); null nếu không có
 * @param actual    giá trị sẽ đạt được nếu phân công; null nếu không áp dụng
 * @param limit     giới hạn của ràng buộc; null nếu không áp dụng
 */
public record Violation(ViolationCode code, String userId, String shiftId, String parameter,
                        Double actual, Double limit, String message) {
}
