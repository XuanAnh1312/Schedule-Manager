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
}
