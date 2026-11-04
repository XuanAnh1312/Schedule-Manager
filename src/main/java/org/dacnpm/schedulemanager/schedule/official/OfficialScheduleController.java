package org.dacnpm.schedulemanager.schedule.official;

import org.dacnpm.schedulemanager.schedule.exception.ExceptionService.Preview;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleService.ChangeResult;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleService.ExceptionOverride;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sửa trực tiếp lịch chính thức (thêm/thay/hủy phân công). Chỉ role admin (/api/schedule/**); vi phạm thuộc nhóm ngoại
 * lệ cần {@code override} kèm quyền schedule.exception.approve. Lỗi: ScheduleErrorAdvice.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/schedule/official")
public class OfficialScheduleController {

    public record AddRequest(String shiftId, String userId, String reason, ExceptionOverride override) {
    }

    public record ReassignRequest(String expectedUserId, String newUserId, String reason, ExceptionOverride override) {
    }

    public record CancelRequest(String expectedUserId, String reason) {
    }

    /** replacingAssignmentId: phân công sẽ bị thay (khi xem trước cho thao tác thay người). */
    public record PreviewRequest(String shiftId, String userId, String replacingAssignmentId) {
    }

    private final OfficialScheduleService service;

    public OfficialScheduleController(OfficialScheduleService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public Preview preview(@RequestBody PreviewRequest r, Authentication auth) {
        return service.preview(required(r.shiftId(), "shiftId"), required(r.userId(), "userId"),
                r.replacingAssignmentId(), actor(auth));
    }

    @PostMapping("/assignments")
    public ResponseEntity<ChangeResult> add(@RequestBody AddRequest r, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addAssignment(required(r.shiftId(), "shiftId"),
                required(r.userId(), "userId"), actor(auth), r.reason(), r.override()));
    }

    @PostMapping("/assignments/{assignmentId}/reassign")
    public ChangeResult reassign(@PathVariable String assignmentId, @RequestBody ReassignRequest r, Authentication auth) {
        return service.reassign(assignmentId, required(r.expectedUserId(), "expectedUserId"),
                required(r.newUserId(), "newUserId"), actor(auth), r.reason(), r.override());
    }

    @PostMapping("/assignments/{assignmentId}/cancel")
    public ChangeResult cancel(@PathVariable String assignmentId, @RequestBody CancelRequest r, Authentication auth) {
        return service.cancelAssignment(assignmentId, required(r.expectedUserId(), "expectedUserId"), actor(auth),
                r.reason());
    }

    private static String actor(Authentication auth) {
        return (String) auth.getPrincipal();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Thiếu thông số " + name + ".");
        }
        return value;
    }
}
