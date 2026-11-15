package org.dacnpm.schedulemanager.api.leave;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * API nghỉ phép cơ bản và vắng đột xuất (BA-04).
 *
 * <pre>
 * GET  /api/leave/types[?includeInactive=true]
 * POST /api/leave                         tạo đơn (nhân viên)
 * GET  /api/leave?scope=mine|all&status=
 * GET  /api/leave/{id}
 * GET  /api/leave/{id}/impact             ca bị ảnh hưởng + độ phủ sau duyệt
 * POST /api/leave/{id}/approve | reject   admin
 * POST /api/leave/{id}/cancel             người tạo (khi còn pending)
 * GET  /api/leave/pending-count           admin
 * POST /api/leave/absences                admin ghi nhận vắng đột xuất
 * GET  /api/leave/absences/report?from=&to=
 * </pre>
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/leave")
public class LeaveController {

    private final LeaveService service;

    public LeaveController(LeaveService service) {
        this.service = service;
    }

    public record DecisionRequest(Integer expectedVersion, String note) {}

    @GetMapping("/types")
    public List<LeaveType> types(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.types(includeInactive, CurrentUser.require());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveView create(@RequestBody LeaveService.CreateLeave request) {
        return service.create(CurrentUser.require(), request);
    }

    @GetMapping
    public List<LeaveView> list(@RequestParam(defaultValue = "mine") String scope,
                                @RequestParam(required = false) String status) {
        return service.list(CurrentUser.require(), scope, status);
    }

    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount() {
        return Map.of("pending", service.pendingCount(CurrentUser.require()));
    }

    /** BAO-04: báo cáo vắng mặt theo nhân viên/bộ phận/loại nghỉ (Admin: toàn công ty; quản lý: cấp dưới). */
    @GetMapping("/absences/summary")
    public LeaveService.AbsenceSummary absenceSummary(
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(defaultValue = "employee") String groupBy) {
        return service.absenceSummary(CurrentUser.require(), from, to, groupBy);
    }

    @GetMapping(value = "/absences/summary.csv", produces = "text/csv;charset=UTF-8")
    public org.springframework.http.ResponseEntity<String> absenceSummaryCsv(
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(defaultValue = "employee") String groupBy) {
        String body = service.absenceSummaryCsv(CurrentUser.require(), from, to, groupBy);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"bao-cao-vang-" + from + "_" + to + ".csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    /** BAO-04: các bước duyệt của đơn. */
    @GetMapping("/{id}/steps")
    public List<org.dacnpm.schedulemanager.api.approvals.ApprovalStep> steps(@PathVariable String id) {
        return service.steps(CurrentUser.require(), id);
    }

    @GetMapping("/absences/report")
    public LeaveService.AbsenceReport absenceReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.absenceReport(CurrentUser.require(), from, to);
    }

    @PostMapping("/absences")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> recordAbsence(@RequestBody LeaveService.AbsenceInput request) {
        return service.recordAbsence(CurrentUser.require(), request);
    }

    @GetMapping("/{id}")
    public LeaveView get(@PathVariable String id) {
        return service.get(CurrentUser.require(), id);
    }

    @GetMapping("/{id}/impact")
    public LeaveImpact impact(@PathVariable String id) {
        return service.impact(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/approve")
    public LeaveView approve(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        DecisionRequest b = body == null ? new DecisionRequest(null, null) : body;
        return service.approve(CurrentUser.require(), id, b.expectedVersion(), b.note());
    }

    @PostMapping("/{id}/reject")
    public LeaveView reject(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        DecisionRequest b = body == null ? new DecisionRequest(null, null) : body;
        return service.reject(CurrentUser.require(), id, b.expectedVersion(), b.note());
    }

    /** BAO-03: Admin hủy đơn đã duyệt (chưa tới ngày nghỉ), hoàn phép. */
    @PostMapping("/{id}/revoke")
    public LeaveView revoke(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        DecisionRequest b = body == null ? new DecisionRequest(null, null) : body;
        return service.revoke(CurrentUser.require(), id, b.expectedVersion(), b.note());
    }

    @PostMapping("/{id}/cancel")
    public LeaveView cancel(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        return service.cancel(CurrentUser.require(), id, body == null ? null : body.expectedVersion());
    }
}
