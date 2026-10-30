package org.dacnpm.schedulemanager.schedule.plan;

import java.util.List;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.DryRunView;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanDiff;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanOptions;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanSummaryView;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.RerunView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API phương án phân lịch. Chỉ role admin truy cập (/api/schedule/** trong SecurityConfig). Lỗi: ScheduleErrorAdvice. */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/schedule/plans")
public class PlanController {

    public record RunRequest(String periodId, List<String> shiftIds, Boolean replaceExisting) {
        PlanOptions options() {
            return new PlanOptions(shiftIds, Boolean.TRUE.equals(replaceExisting));
        }
    }

    public record CreateRequest(String periodId, String name, List<String> shiftIds, Boolean replaceExisting) {
    }

    public record VersionRequest(Integer expectedVersion) {
    }

    public record CancelRequest(Integer expectedVersion, String reason) {
    }

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /** Chạy thử: không lưu gì, lịch chính thức không đổi. */
    @PostMapping("/dry-run")
    public DryRunView dryRun(@RequestBody RunRequest request) {
        return service.dryRun(request.periodId(), request.options());
    }

    @PostMapping
    public ResponseEntity<PlanView> create(@RequestBody CreateRequest request, Authentication authentication) {
        PlanOptions options = new PlanOptions(request.shiftIds(), Boolean.TRUE.equals(request.replaceExisting()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request.periodId(), request.name(), options, actor(authentication)));
    }

    @GetMapping
    public List<PlanSummaryView> list(@RequestParam String periodId) {
        return service.list(periodId);
    }

    @GetMapping("/{id}")
    public PlanView get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/rerun")
    public RerunView rerun(@PathVariable String id, @RequestBody VersionRequest request, Authentication authentication) {
        return service.rerun(id, requireVersion(request.expectedVersion()), actor(authentication));
    }

    @PostMapping("/{id}/cancel")
    public PlanView cancel(@PathVariable String id, @RequestBody CancelRequest request, Authentication authentication) {
        return service.cancel(id, requireVersion(request.expectedVersion()), request.reason(), actor(authentication));
    }

    @GetMapping("/compare")
    public PlanDiff compare(@RequestParam String base, @RequestParam String other) {
        return service.compare(base, other);
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
}
