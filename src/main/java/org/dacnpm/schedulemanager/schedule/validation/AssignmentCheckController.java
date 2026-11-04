package org.dacnpm.schedulemanager.schedule.validation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.shift.ShiftNotFoundException;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCheckService.BatchCheck;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCheckService.LeaveCheck;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCheckService.PublishCheck;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCheckService.SwapCheck;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API kiểm tra phân công cho các luồng thủ công, đổi ca, công bố và nghỉ phép.
 * Chỉ role admin truy cập (/api/schedule/** trong SecurityConfig); luồng của nhân viên sẽ gọi service trực tiếp.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/schedule/checks")
public class AssignmentCheckController {

    public record AssignRequest(String shiftId, String userId, String requiredLevelId) {
    }

    public record BatchRequest(List<AssignRequest> proposals, Set<String> replacedAssignmentIds) {
    }

    public record SwapRequest(String shiftIdA, String userIdA, String shiftIdB, String userIdB) {
    }

    public record PublishRequest(List<String> shiftIds) {
    }

    public record LeaveRequest(String userId, LocalDate from, LocalDate to) {
    }

    private final AssignmentCheckService service;

    public AssignmentCheckController(AssignmentCheckService service) {
        this.service = service;
    }

    @PostMapping("/assign")
    public ValidationResult assign(@RequestBody AssignRequest request) {
        return service.checkAssignment(new AssignmentCandidate(
                required(request.userId(), "userId"), required(request.shiftId(), "shiftId"), request.requiredLevelId()));
    }

    @PostMapping("/batch")
    public BatchResponse batch(@RequestBody BatchRequest request) {
        if (request.proposals() == null) {
            throw new IllegalArgumentException("Thiếu thông số proposals.");
        }
        List<AssignmentCandidate> proposals = request.proposals().stream()
                .map(p -> p == null ? null : new AssignmentCandidate(p.userId(), p.shiftId(), p.requiredLevelId()))
                .toList();
        BatchCheck check = service.checkBatch(proposals, request.replacedAssignmentIds());
        return new BatchResponse(check.allAccepted(), check.acceptedCount(), check.items());
    }

    @PostMapping("/swap")
    public SwapResponse swap(@RequestBody SwapRequest request) {
        SwapCheck check = service.checkSwap(required(request.shiftIdA(), "shiftIdA"), required(request.userIdA(), "userIdA"),
                required(request.shiftIdB(), "shiftIdB"), required(request.userIdB(), "userIdB"));
        return new SwapResponse(check.allowed(), check.userAToShiftB(), check.userBToShiftA());
    }

    @PostMapping("/publish")
    public PublishResponse publish(@RequestBody PublishRequest request) {
        PublishCheck check = service.checkPublish(request.shiftIds());
        return new PublishResponse(check.publishable(), check.needsException(), check.anyUnderstaffed(), check.shifts());
    }

    @PostMapping("/leave")
    public LeaveResponse leave(@RequestBody LeaveRequest request) {
        LeaveCheck check = service.checkLeave(required(request.userId(), "userId"), request.from(), request.to());
        return new LeaveResponse(check.causesUnderstaffing(), check.impacts());
    }

    public record BatchResponse(boolean allAccepted, long acceptedCount, List<AssignmentCheckService.BatchItem> items) {
    }

    public record SwapResponse(boolean allowed, ValidationResult userAToShiftB, ValidationResult userBToShiftA) {
    }

    public record PublishResponse(boolean publishable, boolean needsException, boolean anyUnderstaffed,
                                  List<AssignmentCheckService.ShiftPublishCheck> shifts) {
    }

    public record LeaveResponse(boolean causesUnderstaffing, List<AssignmentCheckService.LeaveImpact> impacts) {
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Thiếu thông số " + name + ".");
        }
        return value;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ShiftNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
