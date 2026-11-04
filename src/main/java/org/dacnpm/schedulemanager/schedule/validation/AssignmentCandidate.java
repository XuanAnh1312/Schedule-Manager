package org.dacnpm.schedulemanager.schedule.validation;

/**
 * Phân công cần kiểm tra.
 *
 * @param requiredLevelId Level của vị trí đang xếp; null nghĩa là suy ra từ cấu hình ca
 */
public record AssignmentCandidate(String userId, String shiftId, String requiredLevelId) {
}
