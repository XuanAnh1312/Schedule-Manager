package org.dacnpm.schedulemanager.schedule.engine;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.dacnpm.schedulemanager.schedule.validation.Violation;

/**
 * Kết quả một lần chạy phân lịch. Chỉ là dữ liệu: không có gì được ghi vào lịch chính thức.
 *
 * @param assignments       phân công mới do lần chạy sinh ra (chưa lưu)
 * @param missingParameters tham số bắt buộc còn thiếu, theo đúng nhân viên và tham số
 */
public record EngineResult(
        Summary summary,
        List<ShiftOutcome> shifts,
        List<PlannedAssignment> assignments,
        List<MissingParameterReport> missingParameters,
        List<EmployeeLoad> employees,
        List<String> warnings) {

    public static final String FULL = "full";
    public static final String BELOW_TARGET = "below_target";
    public static final String UNDERSTAFFED = "understaffed";

    /**
     * @param full           ca đủ tổng nhu cầu
     * @param belowTarget    ca đạt số tối thiểu nhưng chưa đủ tổng nhu cầu
     * @param understaffed   ca dưới số tối thiểu
     * @param unfilledSlots  số vị trí không tìm được người hợp lệ (không bù bằng cách vi phạm ràng buộc)
     * @param rejectionsByCode số lần ứng viên bị loại theo mã vi phạm
     */
    public record Summary(int totalShifts, int full, int belowTarget, int understaffed, int plannedAssignments,
                          int unfilledSlots, Map<String, Long> rejectionsByCode) {
    }

    /** slot: mandatory, preferred, level hoặc flex; levelId chỉ có với vị trí theo Level. */
    public record PlannedAssignment(String shiftId, String userId, String slot, String levelId) {
    }

    /**
     * Vì sao chọn người này: điểm tiêu chí mềm và diễn giải từng thành phần.
     *
     * @param eligibleCandidates số ứng viên qua mọi ràng buộc cứng cho vị trí này
     */
    public record Decision(String userId, String employeeName, String slot, String levelId, double score,
                           List<String> reasons, int eligibleCandidates) {
    }

    /** Lý do một ứng viên bị loại khỏi ca (vi phạm ràng buộc cứng). */
    public record Rejection(String userId, String employeeName, List<Violation> violations) {
    }

    /**
     * @param alreadyAssigned phân công đã lưu của ca (không tính phần được thay thế)
     * @param coverage        full, below_target hoặc understaffed
     */
    public record ShiftOutcome(String shiftId, String name, LocalDate date, LocalTime startTime, LocalTime endTime,
                               int required, int minimum, int alreadyAssigned, int planned, int filled, String coverage,
                               List<Decision> decisions, List<Rejection> rejections, List<String> warnings) {
    }

    /** userId null nghĩa là tham số chung của quy tắc, không thuộc nhân viên cụ thể. */
    public record MissingParameterReport(String userId, String employeeName, String parameter, String message) {
    }

    /**
     * Giờ trong kỳ của nhân viên.
     *
     * @param savedHours   giờ của phân công đã lưu (không tính phân công hủy hoặc đang được thay thế)
     * @param plannedHours giờ của phân công do lần chạy này sinh ra
     */
    public record EmployeeLoad(String userId, String employeeName, Double targetHours, double savedHours,
                               double plannedHours, int plannedShifts) {
    }
}
