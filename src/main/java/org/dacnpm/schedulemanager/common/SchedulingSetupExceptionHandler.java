package org.dacnpm.schedulemanager.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Định dạng lỗi {@code {"error","code","details"}} cho module kỳ/đăng ký/quy tắc (issue #5),
 * giống {@code ApiExceptionHandler} của module yêu cầu. Tách riêng để không sửa phạm vi
 * của handler đó; khi Anh chốt định dạng lỗi chung (A-01) có thể gộp lại.
 */
@RestControllerAdvice(basePackages = {
        "org.dacnpm.schedulemanager.api.periods",
        "org.dacnpm.schedulemanager.api.availability",
        "org.dacnpm.schedulemanager.api.rules"
})
public class SchedulingSetupExceptionHandler {

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
