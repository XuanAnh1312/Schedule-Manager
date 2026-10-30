package org.dacnpm.schedulemanager.api.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {AttendanceController.class, org.dacnpm.schedulemanager.api.report.ReportController.class})
public class AttendanceErrorAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> expected(ResponseStatusException e){
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()==null?"Yêu cầu không hợp lệ":e.getReason()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String,String>> invalid(HttpMessageNotReadableException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error","JSON không hợp lệ"));
    }
}
