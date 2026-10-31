package org.dacnpm.schedulemanager.schedule;

import java.util.LinkedHashMap;
import java.util.Map;
import org.dacnpm.schedulemanager.schedule.plan.PlanException;
import org.dacnpm.schedulemanager.schedule.shift.ShiftNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Dạng lỗi chung của các API lịch: {@code {code, error, details}}. Controller nào có {@code @ExceptionHandler} riêng
 * thì handler riêng được ưu tiên.
 */
@RestControllerAdvice(basePackages = "org.dacnpm.schedulemanager.schedule")
public class ScheduleErrorAdvice {

    @ExceptionHandler(PlanException.class)
    public ResponseEntity<Map<String, Object>> planError(PlanException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("error", e.getMessage());
        if (!e.details().isEmpty()) {
            body.put("details", e.details());
        }
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VALIDATION_FAILED");
        body.put("error", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ShiftNotFoundException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", PlanException.NOT_FOUND);
        body.put("error", e.getMessage());
        return ResponseEntity.status(404).body(body);
    }
}
