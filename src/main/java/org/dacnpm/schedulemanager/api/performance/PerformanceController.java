package org.dacnpm.schedulemanager.api.performance;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.dacnpm.schedulemanager.api.performance.PerformanceModels.*;

/**
 * API hiệu suất/KPI (BAO-06).
 *
 * <pre>
 * GET|POST /api/performance/kpis                 admin: KPI
 * PUT      /api/performance/kpis/{id}            admin sửa / tắt KPI
 * GET|POST /api/performance/cycles               admin: kỳ đánh giá
 * POST     /api/performance/cycles/{id}/open     admin mở kỳ (tạo phiếu, tự tính số liệu)
 * POST     /api/performance/cycles/{id}/close    admin đóng kỳ [?cancelUnfinished=true]
 * GET      /api/performance/reviews?scope=mine|team|all&cycleId=
 * GET      /api/performance/reviews/{id}
 * POST     /api/performance/reviews/{id}/self          nhân viên tự đánh giá
 * POST     /api/performance/reviews/{id}/manager       người đánh giá / admin chấm
 * POST     /api/performance/reviews/{id}/acknowledge   nhân viên phản hồi
 * GET|POST /api/performance/achievements[?employeeId=]  thành tích / đề xuất
 * </pre>
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    public record VersionRequest(Integer expectedVersion) {}

    private final PerformanceService service;

    public PerformanceController(PerformanceService service) {
        this.service = service;
    }

    @GetMapping("/kpis")
    public List<Kpi> kpis(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.kpis(CurrentUser.require(), includeInactive);
    }

    @PostMapping("/kpis")
    @ResponseStatus(HttpStatus.CREATED)
    public Kpi createKpi(@RequestBody KpiInput body) {
        return service.createKpi(CurrentUser.require(), body);
    }

    @PutMapping("/kpis/{id}")
    public Kpi updateKpi(@PathVariable String id, @RequestBody KpiInput body) {
        return service.updateKpi(CurrentUser.require(), id, body);
    }

    @GetMapping("/cycles")
    public List<Cycle> cycles() {
        return service.cycles(CurrentUser.require());
    }

    @PostMapping("/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    public Cycle createCycle(@RequestBody CycleInput body) {
        return service.createCycle(CurrentUser.require(), body);
    }

    @PostMapping("/cycles/{id}/open")
    public Cycle open(@PathVariable String id, @RequestBody(required = false) VersionRequest body) {
        return service.openCycle(CurrentUser.require(), id, body == null ? null : body.expectedVersion());
    }

    @PostMapping("/cycles/{id}/close")
    public Cycle close(@PathVariable String id, @RequestParam(defaultValue = "false") boolean cancelUnfinished,
                       @RequestBody(required = false) VersionRequest body) {
        return service.closeCycle(CurrentUser.require(), id, body == null ? null : body.expectedVersion(), cancelUnfinished);
    }

    @GetMapping("/reviews")
    public List<Review> reviews(@RequestParam(defaultValue = "mine") String scope,
                                @RequestParam(required = false) String cycleId) {
        return service.reviews(CurrentUser.require(), scope, cycleId);
    }

    @GetMapping("/reviews/{id}")
    public Review review(@PathVariable String id) {
        return service.review(CurrentUser.require(), id);
    }

    @PostMapping("/reviews/{id}/self")
    public Review self(@PathVariable String id, @RequestBody SelfReviewInput body) {
        return service.submitSelf(CurrentUser.require(), id, body);
    }

    @PostMapping("/reviews/{id}/manager")
    public Review manager(@PathVariable String id, @RequestBody ManagerReviewInput body) {
        return service.submitManager(CurrentUser.require(), id, body);
    }

    @PostMapping("/reviews/{id}/acknowledge")
    public Review acknowledge(@PathVariable String id, @RequestBody(required = false) AcknowledgeInput body) {
        return service.acknowledge(CurrentUser.require(), id, body == null ? new AcknowledgeInput(null, null) : body);
    }

    @GetMapping("/achievements")
    public List<Achievement> achievements(@RequestParam(required = false) String employeeId) {
        return service.achievements(CurrentUser.require(), employeeId);
    }

    @PostMapping("/achievements")
    @ResponseStatus(HttpStatus.CREATED)
    public Achievement record(@RequestBody AchievementInput body) {
        return service.recordAchievement(CurrentUser.require(), body);
    }
}
