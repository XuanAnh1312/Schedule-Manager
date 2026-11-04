package org.dacnpm.schedulemanager.schedule.plan;

import org.dacnpm.schedulemanager.schedule.exception.ExceptionService;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService.ExceptionView;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService.Preview;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.PlanWorkflowService.PublishReport;
import org.dacnpm.schedulemanager.schedule.plan.PlanWorkflowService.Readiness;
import org.dacnpm.schedulemanager.schedule.plan.PlanWorkflowService.WorkflowView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Luồng duyệt/công bố, chỉnh thủ công/kéo thả và ngoại lệ của một phương án. Chỉ role admin (/api/schedule/**); duyệt
 * ngoại lệ cần thêm quyền schedule.exception.approve. Mọi thao tác ghi cần expectedVersion (khóa lạc quan).
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/schedule/plans/{planId}")
public class PlanWorkflowController {

    public record VersionRequest(Integer expectedVersion) {
    }

    public record ApproveRequest(Integer expectedVersion, String note) {
    }

    public record RejectRequest(Integer expectedVersion, String reason) {
    }

    public record PublishRequest(Integer expectedVersion, Boolean confirmUnderstaffed) {
    }

    public record AssignmentRequest(Integer expectedVersion, String shiftId, String userId, String requiredLevelId) {
    }

    public record MoveRequest(Integer expectedVersion, String userId, String fromShiftId, String toShiftId,
                              String requiredLevelId) {
    }

    public record ExceptionPreviewRequest(String shiftId, String userId, String requiredLevelId) {
    }

    public record ExceptionRequest(Integer expectedVersion, String shiftId, String userId, String requiredLevelId,
                                   String reason, String previewToken, Boolean confirm) {
    }

    private final PlanWorkflowService workflow;
    private final PlanEditService edits;
    private final ExceptionService exceptions;

    public PlanWorkflowController(PlanWorkflowService workflow, PlanEditService edits, ExceptionService exceptions) {
        this.workflow = workflow;
        this.edits = edits;
        this.exceptions = exceptions;
    }

    @GetMapping("/workflow")
    public WorkflowView view(@PathVariable String planId) {
        return workflow.view(planId);
    }

    /** Vi phạm, ngoại lệ còn thiếu, ca thiếu người: xem trước khi gửi duyệt/công bố. */
    @GetMapping("/readiness")
    public Readiness readiness(@PathVariable String planId) {
        return workflow.readiness(planId);
    }

    @PostMapping("/submit")
    public WorkflowView submit(@PathVariable String planId, @RequestBody VersionRequest r, Authentication auth) {
        return workflow.submit(planId, version(r.expectedVersion()), actor(auth));
    }

    @PostMapping("/approve")
    public WorkflowView approve(@PathVariable String planId, @RequestBody ApproveRequest r, Authentication auth) {
        return workflow.approve(planId, version(r.expectedVersion()), r.note(), actor(auth));
    }

    @PostMapping("/reject")
    public WorkflowView reject(@PathVariable String planId, @RequestBody RejectRequest r, Authentication auth) {
        return workflow.reject(planId, version(r.expectedVersion()), r.reason(), actor(auth));
    }

    @PostMapping("/publish")
    public PublishReport publish(@PathVariable String planId, @RequestBody PublishRequest r, Authentication auth) {
        return workflow.publish(planId, version(r.expectedVersion()), Boolean.TRUE.equals(r.confirmUnderstaffed()),
                actor(auth));
    }

    @PostMapping("/assignments")
    public PlanView add(@PathVariable String planId, @RequestBody AssignmentRequest r, Authentication auth) {
        return edits.add(planId, version(r.expectedVersion()), required(r.shiftId(), "shiftId"),
                required(r.userId(), "userId"), r.requiredLevelId(), actor(auth));
    }

    @PostMapping("/assignments/remove")
    public PlanView remove(@PathVariable String planId, @RequestBody AssignmentRequest r, Authentication auth) {
        return edits.remove(planId, version(r.expectedVersion()), required(r.shiftId(), "shiftId"),
                required(r.userId(), "userId"), actor(auth));
    }

    /** Kéo thả nhân viên từ ca này sang ca khác. */
    @PostMapping("/assignments/move")
    public PlanView move(@PathVariable String planId, @RequestBody MoveRequest r, Authentication auth) {
        return edits.move(planId, version(r.expectedVersion()), required(r.userId(), "userId"),
                required(r.fromShiftId(), "fromShiftId"), required(r.toShiftId(), "toShiftId"), r.requiredLevelId(),
                actor(auth));
    }

    @PostMapping("/exceptions/preview")
    public Preview previewException(@PathVariable String planId, @RequestBody ExceptionPreviewRequest r,
                                    Authentication auth) {
        return exceptions.previewForPlan(planId, required(r.shiftId(), "shiftId"), required(r.userId(), "userId"),
                r.requiredLevelId(), actor(auth));
    }

    @PostMapping("/exceptions")
    public ResponseEntity<ExceptionView> approveException(@PathVariable String planId, @RequestBody ExceptionRequest r,
                                                          Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exceptions.approveForPlan(planId,
                version(r.expectedVersion()), required(r.shiftId(), "shiftId"), required(r.userId(), "userId"),
                r.requiredLevelId(), r.reason(), r.previewToken(), r.confirm(), actor(auth)));
    }

    static String actor(Authentication auth) {
        return (String) auth.getPrincipal();
    }

    static int version(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("Thiếu thông số expectedVersion.");
        }
        return expectedVersion;
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Thiếu thông số " + name + ".");
        }
        return value;
    }
}
