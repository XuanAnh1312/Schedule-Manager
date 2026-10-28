package org.dacnpm.schedulemanager.api.requests;

import org.dacnpm.schedulemanager.api.leave.LeaveService;
import org.dacnpm.schedulemanager.api.openshifts.OpenShiftService;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/pending-summary — bộ đếm việc chờ xử lý theo phạm vi người gọi (task v1.1 mục 4.1),
 * dùng cho badge menu "Duyệt yêu cầu" và Dashboard của Bình.
 * <ul>
 *   <li>Nhân viên: số yêu cầu đổi ca đang chờ mình phản hồi.</li>
 *   <li>Admin: thêm yêu cầu đổi ca chờ duyệt, đơn nghỉ chờ duyệt, đăng ký ca trống chờ chọn.</li>
 * </ul>
 */
@Profile("sqlserver")
@RestController
public class PendingSummaryController {

    private final ShiftRequestService shiftRequests;
    private final LeaveService leave;
    private final OpenShiftService openShifts;

    public PendingSummaryController(ShiftRequestService shiftRequests, LeaveService leave, OpenShiftService openShifts) {
        this.shiftRequests = shiftRequests;
        this.leave = leave;
        this.openShifts = openShifts;
    }

    @GetMapping("/api/pending-summary")
    public Map<String, Long> summary() {
        CurrentUser me = CurrentUser.require();
        Map<String, Long> result = new LinkedHashMap<>(shiftRequests.pendingCounts(me));
        if (me.admin()) {
            result.put("leaveAwaitingApproval", leave.pendingCount(me));
            result.put("openShiftClaimsAwaiting", openShifts.pendingClaimCount(me));
            long total = 0;
            for (Map.Entry<String, Long> e : result.entrySet()) {
                if (!"incomingPeer".equals(e.getKey())) total += e.getValue();
            }
            result.put("totalForApprover", total);
        }
        return result;
    }
}
