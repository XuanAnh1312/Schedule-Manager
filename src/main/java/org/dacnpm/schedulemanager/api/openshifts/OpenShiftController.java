package org.dacnpm.schedulemanager.api.openshifts;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * API ca trống (BA-03).
 *
 * <pre>
 * POST   /api/open-shifts                                  admin mở ca trống
 * GET    /api/open-shifts[?status=]                        admin: tất cả; nhân viên: ca còn nhận được + ca đã đăng ký
 * GET    /api/open-shifts/{id}                             chi tiết (+ danh sách đăng ký cho admin)
 * POST   /api/open-shifts/{id}/claims                      nhân viên đăng ký
 * DELETE /api/open-shifts/{id}/claims/mine                 nhân viên rút đăng ký
 * POST   /api/open-shifts/{id}/claims/{claimId}/approve    admin chọn
 * POST   /api/open-shifts/{id}/claims/{claimId}/reject     admin từ chối
 * POST   /api/open-shifts/{id}/close | cancel              admin đóng/hủy
 * </pre>
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/open-shifts")
public class OpenShiftController {

    private final OpenShiftService service;

    public OpenShiftController(OpenShiftService service) {
        this.service = service;
    }

    public record ClaimRequest(String note) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OpenShiftView create(@RequestBody OpenShiftService.CreateOpenShift request) {
        return service.create(CurrentUser.require(), request);
    }

    @GetMapping
    public List<OpenShiftView> list(@RequestParam(required = false) String status) {
        return service.list(CurrentUser.require(), status);
    }

    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount() {
        return Map.of("pendingClaims", service.pendingClaimCount(CurrentUser.require()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return service.detail(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView claim(@PathVariable String id, @RequestBody(required = false) ClaimRequest body) {
        return service.claim(CurrentUser.require(), id, body == null ? null : body.note());
    }

    @DeleteMapping("/{id}/claims/mine")
    public ClaimView withdraw(@PathVariable String id) {
        return service.withdraw(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/claims/{claimId}/approve")
    public ClaimView approve(@PathVariable String id, @PathVariable String claimId,
                             @RequestBody(required = false) OpenShiftService.Decision body) {
        return service.approveClaim(CurrentUser.require(), id, claimId, orEmpty(body));
    }

    @PostMapping("/{id}/claims/{claimId}/reject")
    public ClaimView reject(@PathVariable String id, @PathVariable String claimId,
                            @RequestBody(required = false) OpenShiftService.Decision body) {
        return service.rejectClaim(CurrentUser.require(), id, claimId, orEmpty(body));
    }

    @PostMapping("/{id}/close")
    public OpenShiftView close(@PathVariable String id, @RequestBody(required = false) OpenShiftService.Decision body) {
        return service.close(CurrentUser.require(), id, false, orEmpty(body));
    }

    @PostMapping("/{id}/cancel")
    public OpenShiftView cancel(@PathVariable String id, @RequestBody(required = false) OpenShiftService.Decision body) {
        return service.close(CurrentUser.require(), id, true, orEmpty(body));
    }

    private static OpenShiftService.Decision orEmpty(OpenShiftService.Decision d) {
        return d == null ? new OpenShiftService.Decision(null, null) : d;
    }
}
