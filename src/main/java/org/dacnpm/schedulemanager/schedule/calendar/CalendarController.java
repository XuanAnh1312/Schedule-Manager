package org.dacnpm.schedulemanager.schedule.calendar;

import java.time.LocalDate;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarService.CalendarView;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarService.MySchedule;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarService.Query;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lịch ngày/tuần/tháng.
 * <ul>
 *   <li>{@code GET /api/schedule/calendar}: admin (/api/schedule/**), lịch chính thức hoặc phương án chồng lên.</li>
 *   <li>{@code /api/my/schedule}: mọi người đăng nhập, chỉ lịch đã công bố của chính mình (userId lấy từ JWT).</li>
 * </ul>
 */
@Profile("sqlserver")
@RestController
public class CalendarController {

    public record AcknowledgeRequest(String planId) {
    }

    private final CalendarService service;

    public CalendarController(CalendarService service) {
        this.service = service;
    }

    /** branchId hiện ánh xạ sang địa điểm (locations) vì hệ thống chưa có danh mục chi nhánh riêng. */
    @GetMapping("/api/schedule/calendar")
    public CalendarView calendar(@RequestParam(required = false) String view,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String periodId,
                                 @RequestParam(required = false) String locationId,
                                 @RequestParam(required = false) String branchId,
                                 @RequestParam(required = false) String userId,
                                 @RequestParam(required = false) String levelId,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String groupId,
                                 @RequestParam(required = false) String planId) {
        String location = locationId != null && !locationId.isBlank() ? locationId : branchId;
        return service.calendar(new Query(view, date, from, to, periodId, location, userId, levelId, status, planId,groupId));
    }

    @GetMapping("/api/my/schedule")
    public MySchedule mine(@RequestParam(required = false) String view,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           Authentication auth) {
        return service.mine((String) auth.getPrincipal(), view, date, from, to);
    }

    @PostMapping("/api/my/schedule/viewed")
    public ResponseEntity<Void> viewed(@RequestBody AcknowledgeRequest r, Authentication auth) {
        service.acknowledge((String) auth.getPrincipal(), r.planId(), false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/my/schedule/confirm")
    public ResponseEntity<Void> confirm(@RequestBody AcknowledgeRequest r, Authentication auth) {
        service.acknowledge((String) auth.getPrincipal(), r.planId(), true);
        return ResponseEntity.noContent().build();
    }
}
