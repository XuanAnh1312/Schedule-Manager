package org.dacnpm.schedulemanager.api.requests;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentInfo;
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
import java.util.Map;

/**
 * API đổi ca / nhờ làm thay (BA-01, BA-02).
 *
 * <pre>
 * POST /api/requests                          gửi yêu cầu (nhân viên)
 * GET  /api/requests?scope=mine|all&status=   danh sách (all: admin)
 * GET  /api/requests/{id}
 * POST /api/requests/{id}/accept | decline    người nhận phản hồi
 * POST /api/requests/{id}/approve | reject    admin quyết định
 * POST /api/requests/{id}/cancel              người gửi hủy
 * GET  /api/requests/cover-candidates?assignmentId=
 * GET  /api/requests/swap-options?targetUserId=
 * GET  /api/requests/pending-count
 * </pre>
 * Các thao tác đổi trạng thái nhận {@code expectedVersion} để chặn quyết định trên dữ liệu cũ.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/requests")
public class ShiftRequestController {

    private final ShiftRequestService service;

    public ShiftRequestController(ShiftRequestService service) {
        this.service = service;
    }

    public record DecisionRequest(Integer expectedVersion, String note) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftRequestView create(@RequestBody ShiftRequestService.CreateRequest request) {
        return service.create(CurrentUser.require(), request);
    }

    @GetMapping
    public List<ShiftRequestView> list(@RequestParam(defaultValue = "mine") String scope,
                                       @RequestParam(required = false) String status) {
        return service.list(CurrentUser.require(), scope, status);
    }

    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount() {
        return service.pendingCounts(CurrentUser.require());
    }

    @GetMapping("/cover-candidates")
    public List<ShiftRequestService.Candidate> coverCandidates(@RequestParam String assignmentId) {
        return service.coverCandidates(CurrentUser.require(), assignmentId);
    }

    @GetMapping("/swap-options")
    public List<AssignmentInfo> swapOptions(@RequestParam String targetUserId) {
        return service.swapOptions(CurrentUser.require(), targetUserId);
    }

    @GetMapping("/{id}")
    public ShiftRequestView get(@PathVariable String id) {
        return service.get(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/accept")
    public ShiftRequestView accept(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        DecisionRequest b = orEmpty(body);
        return service.peerRespond(CurrentUser.require(), id, true, b.expectedVersion(), b.note());
    }

    @PostMapping("/{id}/decline")
    public ShiftRequestView decline(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        DecisionRequest b = orEmpty(body);
        return service.peerRespond(CurrentUser.require(), id, false, b.expectedVersion(), b.note());
    }

    @PostMapping("/{id}/approve")
    public ShiftRequestView approve(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        DecisionRequest b = orEmpty(body);
        return service.approve(CurrentUser.require(), id, b.expectedVersion(), b.note());
    }

    @PostMapping("/{id}/reject")
    public ShiftRequestView reject(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        DecisionRequest b = orEmpty(body);
        return service.reject(CurrentUser.require(), id, b.expectedVersion(), b.note());
    }

    @PostMapping("/{id}/cancel")
    public ShiftRequestView cancel(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        return service.cancel(CurrentUser.require(), id, orEmpty(body).expectedVersion());
    }

    private static DecisionRequest orEmpty(DecisionRequest body) {
        return body == null ? new DecisionRequest(null, null) : body;
    }
}
