package org.dacnpm.schedulemanager.api.report;

import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@Profile("sqlserver")
@RestController("attendanceReportController")
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService service;
    public ReportController(ReportService service){this.service=service;}
    @GetMapping("/periods")
    public List<Map<String,Object>> periods(Principal caller){return service.periods(caller.getName());}
    @GetMapping("/dashboard")
    public Map<String,Object> dashboard(Principal caller,@RequestParam String periodId,
                                        @RequestParam(required=false) String userId){
        return service.dashboard(caller.getName(),periodId,userId);
    }
    @GetMapping("/users")
    public List<ReportService.UserHours> users(Principal caller,@RequestParam String periodId,
                                               @RequestParam(required=false) String userId){
        return service.userHours(caller.getName(),periodId,userId);
    }
    @GetMapping("/attendance-status")
    public List<Map<String,Object>> attendanceStatus(Principal caller,@RequestParam String periodId){
        return service.attendanceStatus(caller.getName(),periodId);
    }
    @GetMapping("/shortages")
    public List<Map<String,Object>> shortages(Principal caller,@RequestParam String periodId){
        return service.shortages(caller.getName(),periodId);
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(Principal caller,@RequestParam String periodId,
                                         @RequestParam(defaultValue="csv") String format,
                                         @RequestParam(required=false) String userId){
        var rows=service.userHours(caller.getName(),periodId,userId);
        boolean xlsx="xlsx".equalsIgnoreCase(format);
        if(!xlsx&&!"csv".equalsIgnoreCase(format))return ResponseEntity.badRequest().build();
        byte[] output=xlsx?service.xlsx(rows):service.csv(rows);
        return ResponseEntity.ok()
           .contentType(MediaType.parseMediaType(xlsx?
             "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv; charset=utf-8"))
           .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=attendance-"+periodId.replaceAll("[^a-zA-Z0-9_-]","")+"."+(xlsx?"xlsx":"csv"))
           .header("X-Content-Type-Options","nosniff")
           .body(output);
    }
}
