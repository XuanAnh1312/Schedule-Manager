package org.dacnpm.schedulemanager.schedule.validation;

import java.util.Map;
import org.dacnpm.schedulemanager.schedule.shift.ShiftNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kiểm tra dành cho chính người đăng nhập (nhận ca). Mã nhân viên luôn lấy từ JWT, không nhận từ request, nên nhân
 * viên chỉ kiểm tra được cho bản thân. Dùng cùng {@link AssignmentCheckService} với luồng của admin.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/my/checks")
public class SelfCheckController {

    public record ClaimRequest(String shiftId) {
    }

    private final AssignmentCheckService service;

    public SelfCheckController(AssignmentCheckService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public ValidationResult claim(@RequestBody ClaimRequest request, Authentication authentication) {
        if (request.shiftId() == null || request.shiftId().isBlank()) {
            throw new IllegalArgumentException("Thiếu thông số shiftId.");
        }
        String userId = (String) authentication.getPrincipal();
        return service.checkAssignment(new AssignmentCandidate(userId, request.shiftId(), null));
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
