package org.dacnpm.schedulemanager.domain.schedule;

import java.util.List;
import java.util.Optional;

/**
 * Hợp đồng đọc/sửa lịch chính thức có kiểm soát (chủ sở hữu: Tường).
 *
 * <p>Mọi phương thức sửa phải được gọi bên trong giao dịch của bên gọi; nếu dữ liệu đã đổi so
 * với giá trị mong đợi (người giữ ca khác, phân công đã hủy) thì ném
 * {@link org.dacnpm.schedulemanager.common.ApiException} 409 để toàn bộ giao dịch rollback.
 */
public interface AssignmentMutationService {

    Optional<AssignmentInfo> findAssignment(String assignmentId);

    Optional<ShiftInfo> findShift(String shiftId);

    /** Phân công confirmed của một người trong khoảng ngày (bao gồm hai đầu). */
    List<AssignmentInfo> findConfirmedAssignments(String userId, java.time.LocalDate from, java.time.LocalDate to);

    /** Chuyển phân công sang người khác nếu hiện vẫn do {@code expectedUserId} giữ. */
    void reassign(String assignmentId, String expectedUserId, String newUserId, String actorId, String reason);

    /** Thêm người vào ca; trả về id phân công. */
    String addAssignment(String shiftId, String userId, String actorId, String reason);

    /** Hủy phân công (ví dụ do nghỉ phép được duyệt) nếu hiện vẫn do {@code expectedUserId} giữ. */
    void cancelAssignment(String assignmentId, String expectedUserId, String actorId, String reason);

    /**
     * Đổi ca nguyên tử: {@code firstUserId} nhận ca của {@code secondAssignmentId} và ngược lại. Hai vị trí được kiểm
     * tra theo trạng thái cuối (cả hai phân công cũ đã gỡ); lỗi một bên thì không bên nào thay đổi. Lõi lịch phát một
     * sự kiện lịch duy nhất cho trạng thái cuối, không phát sự kiện trung gian.
     */
    void swap(String firstAssignmentId, String firstUserId, String secondAssignmentId, String secondUserId,
              String actorId, String reason);

    /* ── BAO-02: ngoại lệ khi duyệt đổi/thay ca và workload cho ca trống tự chọn ─────────────────────── */

    /**
     * Ngoại lệ do người có quyền {@code schedule.exception.approve} xác nhận. {@code previewToken} lấy từ bản xem trước
     * tương ứng; dữ liệu đổi giữa lúc xem và lúc duyệt thì token không khớp → 409, phải xem trước lại.
     */
    record ExceptionOverride(String reason, String previewToken, Boolean confirm) {}

    /**
     * Bản xem trước vi phạm cho một vị trí (người {@code userId} nhận ca {@code shiftId}).
     *
     * @param exceptionNeeded có vi phạm thuộc nhóm ngoại lệ
     * @param allowed         được phép duyệt ngoại lệ: cần ngoại lệ và không có ràng buộc cứng
     */
    record ExceptionPreview(String shiftId, String userId, boolean exceptionNeeded, boolean allowed,
                            List<String> blocking, List<String> exceptionable, String previewToken, String message) {}

    /** Giờ đã phân trong kỳ của ca và giờ mục tiêu kỳ (null nếu chưa cấu hình đủ quy tắc). */
    record Workload(double scheduledHours, Double targetHours) {
        /** Tỷ lệ so với mục tiêu; thiếu mục tiêu thì dùng số giờ tuyệt đối để vẫn so sánh được. */
        public double ratio() {
            return targetHours == null || targetHours <= 0 ? scheduledHours : scheduledHours / targetHours;
        }
    }

    /** Xem trước vi phạm khi thay người giữ {@code assignmentId} bằng {@code newUserId}. Không ghi gì. */
    default ExceptionPreview previewReassign(String assignmentId, String newUserId, String actorId) {
        throw new UnsupportedOperationException("previewReassign");
    }

    /**
     * Xem trước hai vị trí của thao tác đổi chéo theo trạng thái cuối. Phần tử 0: {@code secondUserId} nhận ca của
     * {@code firstAssignmentId}; phần tử 1: {@code firstUserId} nhận ca của {@code secondAssignmentId}.
     */
    default List<ExceptionPreview> previewSwap(String firstAssignmentId, String firstUserId, String secondAssignmentId,
                                               String secondUserId, String actorId) {
        throw new UnsupportedOperationException("previewSwap");
    }

    /** Như {@link #reassign} nhưng cho phép vi phạm thuộc nhóm ngoại lệ khi có {@code override} hợp lệ. */
    default void reassign(String assignmentId, String expectedUserId, String newUserId, String actorId, String reason,
                          ExceptionOverride override) {
        if (override != null) throw new UnsupportedOperationException("reassign with exception");
        reassign(assignmentId, expectedUserId, newUserId, actorId, reason);
    }

    /** Như {@link #swap} nhưng mỗi vị trí có thể kèm ngoại lệ (null nếu vị trí đó không cần). */
    default void swap(String firstAssignmentId, String firstUserId, String secondAssignmentId, String secondUserId,
                      String actorId, String reason, ExceptionOverride firstOverride, ExceptionOverride secondOverride) {
        if (firstOverride != null || secondOverride != null) {
            throw new UnsupportedOperationException("swap with exception");
        }
        swap(firstAssignmentId, firstUserId, secondAssignmentId, secondUserId, actorId, reason);
    }

    /** Khối lượng hiện tại của nhân viên trong kỳ chứa ca {@code shiftId} (dùng xếp hạng ca trống tự chọn). */
    default Workload workload(String userId, String shiftId) {
        return new Workload(0, null);
    }
}
