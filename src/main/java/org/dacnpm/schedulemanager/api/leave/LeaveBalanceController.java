package org.dacnpm.schedulemanager.api.leave;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API sổ phép (BAO-03).
 *
 * <pre>
 * GET  /api/leave/balances/me[?year=]                 số dư của tôi
 * GET  /api/leave/balances[?userId=&year=]            admin: số dư theo nhân viên/năm
 * GET  /api/leave/balances/{id}/ledger                sổ cái của một số dư (admin hoặc chủ số dư)
 * POST /api/leave/balances/adjust                     admin điều chỉnh (+/-) có lý do
 * GET  /api/leave/policies[?leaveTypeId=]             admin: chính sách phép
 * POST /api/leave/policies                            admin tạo chính sách
 * POST /api/leave/policies/{id}/end                   admin kết thúc hiệu lực chính sách
 * </pre>
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/leave")
public class LeaveBalanceController {

    private final LeaveBalanceService service;
    private final LeaveRepository leaves;

    public LeaveBalanceController(LeaveBalanceService service, LeaveRepository leaves) {
        this.service = service;
        this.leaves = leaves;
    }

    @GetMapping("/balances/me")
    public List<LeaveBalanceView> mine(@RequestParam(required = false) Integer year) {
        return service.myBalances(CurrentUser.require(), year, leaves.findTypes(false));
    }

    @GetMapping("/balances")
    public List<LeaveBalanceView> balances(@RequestParam(required = false) String userId,
                                           @RequestParam(required = false) Integer year) {
        return service.balances(CurrentUser.require(), userId, year, leaves.findTypes(false));
    }

    @GetMapping("/balances/{id}/ledger")
    public List<LeaveLedgerEntry> ledger(@PathVariable String id) {
        return service.ledger(CurrentUser.require(), id);
    }

    @PostMapping("/balances/adjust")
    public LeaveBalanceView adjust(@RequestBody LeaveBalanceService.AdjustInput body) {
        return service.adjust(CurrentUser.require(), body, type(body.leaveTypeId()));
    }

    @GetMapping("/policies")
    public List<LeavePolicyView> policies(@RequestParam(required = false) String leaveTypeId) {
        return service.policies(CurrentUser.require(), leaveTypeId);
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public LeavePolicyView createPolicy(@RequestBody LeaveBalanceService.PolicyInput body) {
        return service.createPolicy(CurrentUser.require(), body, type(body.leaveTypeId()));
    }

    @PostMapping("/policies/{id}/end")
    public LeavePolicyView endPolicy(@PathVariable String id, @RequestBody LeaveBalanceService.EndPolicyInput body) {
        return service.endPolicy(CurrentUser.require(), id, body);
    }

    private LeaveType type(String id) {
        if (id == null || id.isBlank()) throw ApiException.badRequest("REQUIRED", "Loại nghỉ là bắt buộc.");
        return leaves.findType(id).orElseThrow(() -> ApiException.badRequest("INVALID_LEAVE_TYPE", "Loại nghỉ không hợp lệ."));
    }
}
