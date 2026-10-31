package org.dacnpm.schedulemanager.schedule.shift;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dacnpm.schedulemanager.schedule.shift.ShiftService.CancelResult;
import org.dacnpm.schedulemanager.schedule.shift.ShiftService.RepeatRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API quản lý ca. Chỉ role admin truy cập (/api/schedule/** trong SecurityConfig). */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/schedule/shifts")
public class ShiftController {

    public record UpdateRequest(Integer expectedVersion, ShiftRequest shift) {
    }

    public record BulkRequest(List<ShiftRequest> shifts) {
    }

    public record CloneRequest(List<LocalDate> dates) {
    }

    public record CancelRequest(Integer expectedVersion, String reason) {
    }

    public record EmergencyRequest(String reason, ShiftRequest shift) {
    }

    private final ShiftService service;

    public ShiftController(ShiftService service) {
        this.service = service;
    }

    @GetMapping
    public List<ShiftDetail> search(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) String locationId,
                                    @RequestParam(required = false) String periodId,
                                    @RequestParam(defaultValue = "false") boolean includeCancelled) {
        return service.search(from, to, locationId, includeCancelled).stream()
            .filter(s->periodId==null||periodId.isBlank()||periodId.equals(s.shift().periodId())).toList();
    }

    @GetMapping("/{id}")
    public ShiftDetail get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<ShiftDetail> create(@RequestBody ShiftRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, actor(authentication)));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<ShiftDetail>> createMany(@RequestBody BulkRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createMany(request.shifts(), actor(authentication)));
    }

    @PostMapping("/emergency")
    public ResponseEntity<ShiftDetail> addEmergency(@RequestBody EmergencyRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addEmergency(request.shift(), request.reason(), actor(authentication)));
    }

    @PutMapping("/{id}")
    public ShiftDetail update(@PathVariable String id, @RequestBody UpdateRequest request, Authentication authentication) {
        return service.update(id, requireVersion(request.expectedVersion()), request.shift(), actor(authentication));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication authentication) {
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<List<ShiftDetail>> cloneTo(@PathVariable String id, @RequestBody CloneRequest request,
                                                     Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.cloneTo(id, request.dates(), actor(authentication)));
    }

    @PostMapping("/{id}/repeat")
    public ResponseEntity<List<ShiftDetail>> repeat(@PathVariable String id, @RequestBody RepeatRequest request,
                                                    Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.repeat(id, request, actor(authentication)));
    }

    @PostMapping("/{id}/cancel")
    public CancelResult cancel(@PathVariable String id, @RequestBody CancelRequest request, Authentication authentication) {
        return service.cancel(id, requireVersion(request.expectedVersion()), request.reason(), actor(authentication));
    }

    private static String actor(Authentication authentication) {
        return (String) authentication.getPrincipal();
    }

    private static int requireVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("Thiếu thông số expectedVersion.");
        }
        return expectedVersion;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ShiftNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ShiftVersionConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ShiftVersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ShiftRejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(ShiftRejectedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("errors", e.errors());
        body.put("violations", e.violations());
        body.put("warnings", e.warnings());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}
