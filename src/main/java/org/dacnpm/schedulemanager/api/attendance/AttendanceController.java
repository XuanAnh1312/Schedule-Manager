package org.dacnpm.schedulemanager.api.attendance;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.util.*;

@Profile("sqlserver")
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {
    private final AttendanceService service;
    public AttendanceController(AttendanceService service){this.service=service;}
    private String uid(Principal user){
        if(user==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bạn cần đăng nhập API");
        return user.getName(); // JWT subject, NEVER a request-body-supplied user ID
    }
    public record ShiftInput(String shiftId){}
    public record LockInput(String reason){}

    @GetMapping("/me")
    public Map<String,Object> me(Principal user){return service.me(uid(user));}
    @PostMapping("/check-in")
    public Map<String,Object> checkIn(Principal user,@RequestBody ShiftInput request){
        if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Thiếu thông tin ca");
        return service.checkIn(uid(user),request.shiftId());
    }
    @PostMapping("/{recordId}/check-out")
    public Map<String,Object> checkOut(Principal user,@PathVariable String recordId,
                                       @RequestBody(required=false) AttendanceService.CheckOut request){
        return service.checkOut(uid(user),recordId,request==null?0:request.breakMinutes());
    }
    @GetMapping("/shifts")
    public List<Map<String,Object>> shifts(Principal user){return service.shifts(uid(user));}
    @GetMapping("/records")
    public List<Map<String,Object>> records(Principal user,@RequestParam(required=false) String userId,
                                           @RequestParam(required=false) String periodId){
        return service.records(uid(user),userId,periodId);
    }
    @PostMapping("/requests")
    public Map<String,Object> request(Principal user,@RequestBody AttendanceService.Change change){
        return service.request(uid(user),change);
    }
    @GetMapping("/pending-approval")
    public List<Map<String,Object>> pendingPunches(Principal user,@RequestParam(required=false) String periodId){
        return service.pendingPunchApproval(uid(user),periodId);
    }
    @PostMapping("/{recordId}/approve")
    public Map<String,Object> approvePunch(Principal user,@PathVariable String recordId,@RequestBody LockInput input){
        return service.approvePunch(uid(user),recordId,input==null?null:input.reason());
    }
    @GetMapping("/requests")
    public List<Map<String,Object>> requests(Principal user,@RequestParam(required=false) String status){
        return service.requests(uid(user),status);
    }
    @PostMapping("/requests/{id}/review")
    public Map<String,Object> review(Principal user,@PathVariable String id,
                                     @RequestBody AttendanceService.Review review){
        return service.review(uid(user),id,review);
    }
    @PostMapping("/periods/{periodId}/lock")
    public Map<String,Object> lock(Principal user,@PathVariable String periodId,@RequestBody LockInput input){
        return service.lockPeriod(uid(user),periodId,input==null?null:input.reason());
    }
    @GetMapping("/audit")
    public List<Map<String,Object>> audit(Principal user,@RequestParam(required=false) String userId){
        return service.auditHistory(uid(user),userId);
    }
}
