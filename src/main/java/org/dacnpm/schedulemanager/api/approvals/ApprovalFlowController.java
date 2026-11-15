package org.dacnpm.schedulemanager.api.approvals;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Cấu hình quy trình duyệt (BAO-04).
 *
 * <pre>
 * GET /api/approval-flows        admin: danh sách cấu hình
 * PUT /api/approval-flows        admin: đặt quy trình cho (subjectType, leaveTypeId)
 * GET /api/approval-flows/me     tôi có cấp dưới không (để UI hiện hộp duyệt của quản lý)
 * </pre>
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/approval-flows")
public class ApprovalFlowController {

    private final ApprovalFlowService service;

    public ApprovalFlowController(ApprovalFlowService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApprovalFlow> flows() {
        return service.flows(CurrentUser.require());
    }

    @PutMapping
    public ApprovalFlow set(@RequestBody ApprovalFlowService.FlowInput body) {
        return service.setFlow(CurrentUser.require(), body);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser me = CurrentUser.require();
        return Map.of("manager", service.isManager(me), "admin", me.admin());
    }
}
