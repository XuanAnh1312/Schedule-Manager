package org.dacnpm.schedulemanager.schedule.report;

import org.dacnpm.schedulemanager.schedule.report.ReportModels.AssignmentsReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.HoursReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.ShiftsReport;
import org.dacnpm.schedulemanager.schedule.report.ReportModels.VersionsReport;
import org.dacnpm.schedulemanager.schedule.report.ReportService.Filters;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API dữ liệu báo cáo lịch cho module báo cáo/Dashboard. Chỉ role admin (/api/schedule/**). Mọi báo cáo có
 * {@code context}; {@code source} = published (mặc định) hoặc plan (kèm planId), không bao giờ gộp hai nguồn.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/schedule/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/versions")
    public VersionsReport versions(@RequestParam String periodId) {
        return service.versions(periodId);
    }

    @GetMapping("/hours")
    public HoursReport hours(@RequestParam(required = false) String periodId,
                             @RequestParam(required = false) String source,
                             @RequestParam(required = false) String planId,
                             @RequestParam(required = false) String userId,
                             @RequestParam(required = false) String levelId,
                             @RequestParam(required = false) String locationId) {
        return service.hours(periodId, source, planId, new Filters(userId, levelId, locationId));
    }

    @GetMapping("/assignments")
    public AssignmentsReport assignments(@RequestParam(required = false) String periodId,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) String planId,
                                         @RequestParam(required = false) String userId,
                                         @RequestParam(required = false) String levelId,
                                         @RequestParam(required = false) String locationId) {
        return service.assignments(periodId, source, planId, new Filters(userId, levelId, locationId));
    }

    @GetMapping("/shifts")
    public ShiftsReport shifts(@RequestParam(required = false) String periodId,
                               @RequestParam(required = false) String source,
                               @RequestParam(required = false) String planId,
                               @RequestParam(required = false) String locationId) {
        return service.shifts(periodId, source, planId, new Filters(null, null, locationId));
    }
}
