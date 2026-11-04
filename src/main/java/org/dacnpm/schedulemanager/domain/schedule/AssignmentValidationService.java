package org.dacnpm.schedulemanager.domain.schedule;

import java.util.List;
import java.util.Set;

/**
 * Hợp đồng kiểm tra phân công dùng chung (chủ sở hữu: Tường — task v1.1 mục 4.1).
 *
 * <p>Module đổi ca/ca trống/nghỉ phép gọi dịch vụ này <b>tại thời điểm duyệt</b> (BA-02),
 * không chỉ lúc gửi yêu cầu. Bản cài đặt tạm {@link InterimJdbcScheduleAdapter} sẽ được
 * thay bằng validator đầy đủ của Tường (trần giờ AC-01, chứng chỉ, …) mà không đổi chữ ký.
 */
public interface AssignmentValidationService {

    /**
     * @param userId               người sẽ nhận ca
     * @param shiftId              ca cần nhận
     * @param replacingUserId      người đang giữ vị trí (đổi/thay ca) — để kiểm tra Level tương đương; null nếu không có
     * @param ignoreAssignmentIds  phân công của {@code userId} sẽ bị gỡ trong cùng thao tác (ví dụ ca đem đổi chéo)
     * @param requiredLevelId      Level yêu cầu cụ thể (ca trống); null = suy ra từ ca/người được thay
     */
    List<Violation> validate(String userId, String shiftId, String replacingUserId,
                             Set<String> ignoreAssignmentIds, String requiredLevelId);
}
