package org.dacnpm.schedulemanager.api.events;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Vận hành outbox (BAO-01) — chỉ Admin. Kiểm tra quyền nằm ở {@link OutboxAdminService}.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/admin/events")
public class OutboxAdminController {

    private final OutboxAdminService service;

    public OutboxAdminController(OutboxAdminService service) {
        this.service = service;
    }

    /** GET /api/admin/events?status=problem|all|pending|processing|processed|failed|dead&type=&from=&to=&page=0&size=20 */
    @GetMapping
    public OutboxEventPage list(@RequestParam(defaultValue = "problem") String status,
                                @RequestParam(required = false) String type,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return service.list(CurrentUser.require(), status, type, from, to, page, size);
    }

    /** GET /api/admin/events/summary — số sự kiện theo trạng thái. */
    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return service.summary(CurrentUser.require());
    }

    /** POST /api/admin/events/{id}/retry  body: {"reason":"...", "dispatchNow":true} */
    @PostMapping("/{id}/retry")
    public OutboxEventView retry(@PathVariable String id, @RequestBody RetryRequest request) {
        boolean now = request.dispatchNow() == null || request.dispatchNow();
        return service.retry(CurrentUser.require(), id, request.reason(), now);
    }

    /** POST /api/admin/events/retry  body: {"status":"dead|failed|problem", "type":null, "reason":"..."} */
    @PostMapping("/retry")
    public Map<String, Object> retryAll(@RequestBody BulkRetryRequest request) {
        return service.retryAll(CurrentUser.require(), request.status(), request.type(), request.reason());
    }

    public record RetryRequest(String reason, Boolean dispatchNow) {}

    public record BulkRetryRequest(String status, String type, String reason) {}
}
