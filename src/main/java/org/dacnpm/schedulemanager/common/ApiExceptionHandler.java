package org.dacnpm.schedulemanager.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chuyển {@link ApiException} thành JSON thống nhất:
 * {@code {"error": "...", "code": "...", "details": [...]}}.
 *
 * <p>Giới hạn ở các package API của module yêu cầu/nghỉ phép/thông báo để không
 * thay đổi hành vi các controller khác; Anh có thể mở rộng phạm vi khi chốt
 * định dạng lỗi chung.
 */
@RestControllerAdvice(basePackages = {
        "org.dacnpm.schedulemanager.api.requests",
        "org.dacnpm.schedulemanager.api.leave",
        "org.dacnpm.schedulemanager.api.openshifts",
        "org.dacnpm.schedulemanager.api.notifications"
})
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("code", ex.getCode());
        if (!ex.getDetails().isEmpty()) {
            body.put("details", ex.getDetails());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }
}
