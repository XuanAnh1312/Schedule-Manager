package org.dacnpm.schedulemanager.api.attendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** SQL Server is the source of truth; no browser-local timesheet or trusted client user ID. */
@Profile("sqlserver")
@Service
public class AttendanceService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    public AttendanceService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db; this.mapper = mapper;
    }

    public record Change(String shiftId, String recordId, String clockIn, String clockOut,
                         Integer breakMinutes, String reason) {}
    public record Review(Boolean approve, String reason) {}
    public record CheckOut(Integer breakMinutes) {}

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
    private String newId() { return UUID.randomUUID().toString(); }
    private LocalDateTime utcNow() { return LocalDateTime.now(ZoneOffset.UTC); }
    private LocalDateTime parse(String value) {
        try { return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC); }
        catch (RuntimeException ex) { throw error(HttpStatus.BAD_REQUEST, "Thời gian phải là ISO-8601 có múi giờ, ví dụ 2026-10-31T09:00:00Z"); }
    }
    private String iso(LocalDateTime time) { return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString(); }
    private String asJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private List<Map<String,Object>> query(String sql, Object... args) {
        return db.query(sql, (rs,n) -> {
            Map<String,Object> item = new LinkedHashMap<>();
            for(int i=1;i<=rs.getMetaData().getColumnCount();i++){
                String key=rs.getMetaData().getColumnLabel(i);
                Object v=rs.getObject(i);
                if(v instanceof java.sql.Timestamp t) v=iso(t.toLocalDateTime());
                else if(v instanceof java.sql.Date d) v=d.toLocalDate().toString();
                else if(v instanceof java.sql.Time t) v=t.toLocalTime().toString();
                item.put(key,v);
            }
            return item;
        }, args);
    }
    private Map<String,Object> one(String sql, Object... args) {
        var rows=query(sql,args); return rows.isEmpty()?null:rows.get(0);
    }
    public boolean isAdmin(String caller) {
        Map<String,Object> u=one("SELECT role,status FROM users WHERE id=?",caller);
        if(u==null || !"active".equals(u.get("status"))) throw error(HttpStatus.FORBIDDEN,"Tài khoản bị khóa hoặc không tồn tại");
        return "admin".equals(u.get("role"));
    }
    public Map<String,Object> me(String actor) {
        isAdmin(actor);
        Map<String,Object> user=one("SELECT id,full_name,role,status FROM users WHERE id=?",actor);
        return user == null ? Map.of() : user;
    }
    private void checkUser(String caller,String target) {
        boolean admin=isAdmin(caller);
        if(!admin && !caller.equals(target)) throw error(HttpStatus.FORBIDDEN,"Chỉ được xem dữ liệu của bản thân");
    }
    private void needAdmin(String caller) {
        if(!isAdmin(caller)) throw error(HttpStatus.FORBIDDEN,"Chỉ quản trị viên được phép duyệt hoặc khóa kỳ");
    }
    private Map<String,Object> shift(String user,String shiftId) {
        Map<String,Object> row=one("""
            SELECT s.id,s.shift_date,s.start_time,s.end_time,s.status,a.status AS assignment_status
            FROM shifts s JOIN assignments a ON a.shift_id=s.id AND a.user_id=?
            WHERE s.id=?
            """,user,shiftId);
        if(row==null || !"published".equals(row.get("status")) || !"confirmed".equals(row.get("assignment_status")))
            throw error(HttpStatus.CONFLICT,"Chỉ chấm công cho ca đã công bố và đã phân cho nhân viên");
        return row;
    }
    private int setting(String key) {
        return db.queryForObject("SELECT "+switch(key){
            case "early_checkin_minutes" -> "early_checkin_minutes";
            case "late_grace_minutes" -> "late_grace_minutes";
            case "max_shift_hours" -> "max_shift_hours";
            default -> throw new IllegalArgumentException("policy");
        }+" FROM attendance_policies WHERE id='default'",Integer.class);
    }
    private ZoneId zone() {
        return ZoneId.of(db.queryForObject("SELECT timezone_name FROM attendance_policies WHERE id='default'",String.class));
    }
    private LocalDateTime[] shiftWindow(Map<String,Object> shift) {
        LocalDate date=LocalDate.parse((String)shift.get("shift_date"));
        LocalTime start=LocalTime.parse((String)shift.get("start_time")), end=LocalTime.parse((String)shift.get("end_time"));
        ZonedDateTime s=ZonedDateTime.of(date,start,zone());
        ZonedDateTime e=ZonedDateTime.of(end.compareTo(start)<=0?date.plusDays(1):date,end,zone());
        return new LocalDateTime[]{LocalDateTime.ofInstant(s.toInstant(),ZoneOffset.UTC),LocalDateTime.ofInstant(e.toInstant(),ZoneOffset.UTC)};
    }
    private void unlocked(String shiftId) {
        Integer locked=db.queryForObject("""
             SELECT COUNT(*) FROM attendance_period_locks l JOIN periods p ON p.id=l.period_id
             JOIN shifts s ON s.shift_date BETWEEN p.start_date AND p.end_date WHERE s.id=?
             """,Integer.class,shiftId);
        if(locked!=null && locked>0) throw error(HttpStatus.CONFLICT,"Kỳ chấm công đã khóa. Hãy gửi yêu cầu điều chỉnh có lý do.");
    }
    private void audit(String recordId,String reqId,String owner,String actor,String action,Object before,Object after,String reason) {
        db.update("INSERT INTO attendance_audit(id,record_id,request_id,user_id,actor_id,action,before_json,after_json,reason) VALUES(?,?,?,?,?,?,?,?,?)",
            newId(),recordId,reqId,owner,actor,action,before==null?null:asJson(before),after==null?null:asJson(after),reason);
    }
    private Map<String,Object> record(String recordId) {
        return one("SELECT id,user_id,shift_id,clock_in_at,clock_out_at,break_minutes,status,version,updated_at FROM attendance_records WHERE id=?",recordId);
    }
    @Transactional
    public Map<String,Object> checkIn(String actor,String shiftId) {
        isAdmin(actor); // validates active DB user even if JWT is still valid
        if(shiftId==null || shiftId.isBlank()) throw error(HttpStatus.BAD_REQUEST,"Thiếu shiftId");
        Map<String,Object> s=shift(actor,shiftId);
        unlocked(shiftId);
        LocalDateTime now=utcNow();
        LocalDateTime[] window=shiftWindow(s);
        if(now.isBefore(window[0].minusMinutes(setting("early_checkin_minutes"))) || !now.isBefore(window[1]))
            throw error(HttpStatus.CONFLICT,"Không nằm trong khung giờ check-in hợp lệ của ca");
        if(one("SELECT id FROM attendance_records WITH (UPDLOCK,HOLDLOCK) WHERE user_id=? AND clock_out_at IS NULL",actor)!=null)
            throw error(HttpStatus.CONFLICT,"Bạn đang có lượt chấm công chưa check-out");
        String id=newId();
        try {
            db.update("INSERT INTO attendance_records(id,user_id,shift_id,clock_in_at) VALUES(?,?,?,?)",id,actor,shiftId,now);
        } catch(DataIntegrityViolationException e) { throw error(HttpStatus.CONFLICT,"Ca đã chấm công hoặc còn lượt mở trùng"); }
        Map<String,Object> after=record(id);
        audit(id,null,actor,actor,"CHECK_IN",null,after,null);
        return after;
    }
    @Transactional
    public Map<String,Object> checkOut(String actor,String recordId,Integer breakMinutes) {
        isAdmin(actor);
        if(recordId==null || recordId.isBlank()) throw error(HttpStatus.BAD_REQUEST,"Thiếu recordId");
        Map<String,Object> before=one("SELECT id,user_id,shift_id,clock_in_at,clock_out_at,break_minutes,status,version,updated_at FROM attendance_records WITH (UPDLOCK,HOLDLOCK) WHERE id=?",recordId);
        if(before==null) throw error(HttpStatus.NOT_FOUND,"Không tìm thấy lượt công");
        if(!actor.equals(before.get("user_id"))) throw error(HttpStatus.FORBIDDEN,"Không thể check-out thay người khác");
        if(before.get("clock_out_at")!=null) throw error(HttpStatus.CONFLICT,"Lượt đã check-out");
        unlocked((String)before.get("shift_id"));
        LocalDateTime start=parse((String)before.get("clock_in_at"));
        LocalDateTime now=utcNow();
        long minutes=Duration.between(start,now).toMinutes();
        int br=breakMinutes==null?0:breakMinutes;
        if(minutes<=0 || minutes>setting("max_shift_hours")*60L || br<0 || br>=minutes)
            throw error(HttpStatus.BAD_REQUEST,"Giờ check-out hoặc thời gian nghỉ không hợp lệ");
        db.update("UPDATE attendance_records SET clock_out_at=?,break_minutes=?,status='completed',version=version+1,updated_at=SYSUTCDATETIME() WHERE id=?",now,br,recordId);
        Map<String,Object> after=record(recordId);
        audit(recordId,null,actor,actor,"CHECK_OUT",before,after,null);
        return after;
    }
    /** Available shifts are assigned, published and scoped to the authenticated person. */
    public List<Map<String,Object>> shifts(String actor) {
        isAdmin(actor);
        return query("""
           SELECT s.id,s.name,s.shift_date,s.start_time,s.end_time,s.status
           FROM shifts s JOIN assignments a ON a.shift_id=s.id
           WHERE a.user_id=? AND a.status='confirmed' AND s.status='published'
           ORDER BY s.shift_date DESC,s.start_time DESC
           """,actor);
    }
    public List<Map<String,Object>> records(String actor,String userId,String periodId) {
        boolean admin=isAdmin(actor);
        String target=(userId==null||userId.isBlank())?actor:userId;
        if(!admin && !target.equals(actor)) throw error(HttpStatus.FORBIDDEN,"Không có quyền xem công người khác");
        List<Map<String,Object>> rows=query("""
           SELECT r.id,r.user_id,u.full_name,r.shift_id,s.name AS shift_name,s.shift_date,s.start_time,s.end_time,
             r.clock_in_at,r.clock_out_at,r.break_minutes,r.status,r.version,
             CASE WHEN r.clock_out_at IS NULL THEN NULL ELSE
               (DATEDIFF(MINUTE,r.clock_in_at,r.clock_out_at)-r.break_minutes)/60.0 END AS worked_hours
           FROM attendance_records r JOIN users u ON u.id=r.user_id
           JOIN shifts s ON s.id=r.shift_id
           WHERE r.user_id=? AND (? IS NULL OR EXISTS(
               SELECT 1 FROM periods p WHERE p.id=? AND s.shift_date BETWEEN p.start_date AND p.end_date))
           ORDER BY r.clock_in_at DESC
           """,target,periodId,periodId);
        for(Map<String,Object> r:rows){
            Map<String,Object> sh=Map.of("shift_date",r.get("shift_date"),"start_time",r.get("start_time"),"end_time",r.get("end_time"));
            LocalDateTime[] window=shiftWindow(sh);
            LocalDateTime start=parse((String)r.get("clock_in_at"));
            r.put("lateMinutes",AttendanceMath.lateMinutes(start,window[0],setting("late_grace_minutes")));
            if(r.get("clock_out_at")!=null){
                LocalDateTime end=parse((String)r.get("clock_out_at"));
                int br=(int)r.get("break_minutes");
                r.put("overtimeMinutes",AttendanceMath.overtimeMinutes(start,end,br,window[0],window[1]));
                r.put("nightMinutesBeforeBreak",AttendanceMath.nightMinutes(start,end,zone()));
            }else{r.put("overtimeMinutes",null);r.put("nightMinutesBeforeBreak",null);}
        }
        return rows;
    }
    private void validateProposal(Map<String,Object> shift,LocalDateTime start,LocalDateTime end,int br) {
        long mins=Duration.between(start,end).toMinutes();
        LocalDateTime[] w=shiftWindow(shift);
        if(mins<1 || mins>setting("max_shift_hours")*60L || br<0 || br>=mins ||
           start.isBefore(w[0].minusHours(12)) || end.isAfter(w[1].plusHours(12)))
            throw error(HttpStatus.BAD_REQUEST,"Giờ đề xuất không hợp lệ so với ca hoặc số phút nghỉ");
    }
    @Transactional
    public Map<String,Object> request(String actor,Change payload) {
        isAdmin(actor);
        if(payload==null || payload.reason()==null || payload.reason().trim().length()<5)
            throw error(HttpStatus.BAD_REQUEST,"Lý do điều chỉnh cần ít nhất 5 ký tự");
        Map<String,Object> existing=null;
        String shiftId=payload.shiftId();
        if(payload.recordId()!=null && !payload.recordId().isBlank()) {
            existing=record(payload.recordId());
            if(existing==null) throw error(HttpStatus.NOT_FOUND,"Lượt chấm công không tồn tại");
            if(!actor.equals(existing.get("user_id"))) throw error(HttpStatus.FORBIDDEN,"Chỉ được sửa công cá nhân");
            if(existing.get("clock_out_at")==null) throw error(HttpStatus.CONFLICT,"Cần check-out trước khi yêu cầu sửa công");
            shiftId=(String)existing.get("shift_id");
        }
        if(shiftId==null || shiftId.isBlank()) throw error(HttpStatus.BAD_REQUEST,"Thiếu shiftId");
        Map<String,Object> s=shift(actor,shiftId);
        if(existing==null && one("SELECT id FROM attendance_records WHERE user_id=? AND shift_id=?",actor,shiftId)!=null)
            throw error(HttpStatus.CONFLICT,"Ca đã có công: hãy nhập recordId để gửi yêu cầu sửa, không bổ sung trùng");
        LocalDateTime start=parse(payload.clockIn()), end=parse(payload.clockOut());
        int br=payload.breakMinutes()==null?0:payload.breakMinutes();
        validateProposal(s,start,end,br);
        if(one("SELECT id FROM attendance_adjustment_requests WITH (UPDLOCK,HOLDLOCK) WHERE user_id=? AND shift_id=? AND status='pending'",actor,shiftId)!=null)
            throw error(HttpStatus.CONFLICT,"Đã có yêu cầu đang chờ duyệt cho ca này");
        String id=newId();
        String after=asJson(Map.of("clockIn",iso(start),"clockOut",iso(end),"breakMinutes",br));
        try {
            db.update("""
               INSERT INTO attendance_adjustment_requests(id,user_id,shift_id,record_id,proposed_in_at,proposed_out_at,proposed_break_minutes,reason,before_json,after_json,base_record_version)
               VALUES(?,?,?,?,?,?,?,?,?,?,?)
               """,id,actor,shiftId,existing==null?null:existing.get("id"),start,end,br,payload.reason().trim(),existing==null?null:asJson(existing),after,existing==null?null:existing.get("version"));
        } catch(DataIntegrityViolationException ex) {
            throw error(HttpStatus.CONFLICT,"Yêu cầu trùng hoặc dữ liệu vi phạm ràng buộc");
        }
        audit(existing==null?null:(String)existing.get("id"),id,actor,actor,"REQUEST_ADJUSTMENT",existing,Map.of("requestId",id,"proposal",after),payload.reason());
        return one("SELECT id,user_id,shift_id,record_id,status,requested_at,reason FROM attendance_adjustment_requests WHERE id=?",id);
    }
    public List<Map<String,Object>> pendingPunchApproval(String actor,String periodId) {
        needAdmin(actor);
        return query("""
          SELECT r.id,r.user_id,u.full_name,r.shift_id,s.name AS shift_name,s.shift_date,
                 r.clock_in_at,r.clock_out_at,r.break_minutes,r.status,r.version
          FROM attendance_records r JOIN users u ON u.id=r.user_id
          JOIN shifts s ON s.id=r.shift_id
          WHERE r.status='completed' AND (? IS NULL OR EXISTS(
              SELECT 1 FROM periods p WHERE p.id=? AND s.shift_date BETWEEN p.start_date AND p.end_date))
          ORDER BY r.clock_out_at DESC
        """,periodId,periodId);
    }
    @Transactional
    public Map<String,Object> approvePunch(String actor,String recordId,String reason) {
        needAdmin(actor);
        if(reason==null||reason.trim().length()<5)
            throw error(HttpStatus.BAD_REQUEST,"Cần ghi lý do duyệt ít nhất 5 ký tự");
        Map<String,Object> before=one("""
          SELECT id,user_id,shift_id,clock_in_at,clock_out_at,break_minutes,status,version,updated_at
          FROM attendance_records WITH(UPDLOCK,HOLDLOCK) WHERE id=?
        """,recordId);
        if(before==null)throw error(HttpStatus.NOT_FOUND,"Không tìm thấy lượt công");
        if(actor.equals(before.get("user_id")))throw error(HttpStatus.FORBIDDEN,"Không được tự duyệt giờ công của mình");
        if(!"completed".equals(before.get("status")))
            throw error(HttpStatus.CONFLICT,"Chỉ duyệt được lượt đã check-out và chưa duyệt");
        int count=db.update("UPDATE attendance_records SET status='approved',version=version+1,updated_at=SYSUTCDATETIME() WHERE id=? AND status='completed' AND version=?",
            recordId,before.get("version"));
        if(count!=1)throw error(HttpStatus.CONFLICT,"Bản ghi đã bị xử lý đồng thời");
        Map<String,Object> after=record(recordId);
        audit(recordId,null,(String)before.get("user_id"),actor,"APPROVE_PUNCH",before,after,reason);
        return after;
    }
    public List<Map<String,Object>> requests(String actor,String status) {
        boolean admin=isAdmin(actor);
        String filter=(status==null||status.isBlank())?"pending":status;
        if(!List.of("pending","approved","rejected","all").contains(filter)) throw error(HttpStatus.BAD_REQUEST,"Trạng thái không hợp lệ");
        return query("""
           SELECT q.id,q.user_id,u.full_name,q.shift_id,q.record_id,q.proposed_in_at,q.proposed_out_at,
               q.proposed_break_minutes,q.reason,q.status,q.requested_at,q.resolved_by,q.resolved_at,q.resolution_reason,
               q.before_json,q.after_json
           FROM attendance_adjustment_requests q JOIN users u ON u.id=q.user_id
           WHERE (?=1 OR q.user_id=?) AND (?='all' OR q.status=?) ORDER BY q.requested_at DESC
           """,admin?1:0,actor,filter,filter);
    }
    @Transactional
    public Map<String,Object> review(String actor,String requestId,Review review) {
        needAdmin(actor);
        if(review==null || review.approve()==null || review.reason()==null || review.reason().trim().length()<5)
            throw error(HttpStatus.BAD_REQUEST,"Cần ghi lý do duyệt/từ chối (ít nhất 5 ký tự)");
        Map<String,Object> q=one("""
             SELECT id,user_id,shift_id,record_id,proposed_in_at,proposed_out_at,proposed_break_minutes,status,reason,base_record_version
             FROM attendance_adjustment_requests WITH (UPDLOCK,HOLDLOCK) WHERE id=?
             """,requestId);
        if(q==null) throw error(HttpStatus.NOT_FOUND,"Không tìm thấy yêu cầu");
        if(!"pending".equals(q.get("status"))) throw error(HttpStatus.CONFLICT,"Yêu cầu đã được xử lý");
        if(actor.equals(q.get("user_id"))) throw error(HttpStatus.FORBIDDEN,"Không được tự duyệt yêu cầu của mình");
        String owner=(String)q.get("user_id");
        String recordId=(String)q.get("record_id");
        Map<String,Object> before=recordId==null?null:record(recordId);
        Map<String,Object> after=null;
        if(review.approve()) {
            shift(owner,(String)q.get("shift_id"));
            if(recordId==null) {
                if(one("SELECT id FROM attendance_records WITH (UPDLOCK,HOLDLOCK) WHERE user_id=? AND shift_id=?",owner,q.get("shift_id"))!=null)
                    throw error(HttpStatus.CONFLICT,"Ca đã có lượt công; tải lại trước khi duyệt");
                recordId=newId();
                db.update("INSERT INTO attendance_records(id,user_id,shift_id,clock_in_at,clock_out_at,break_minutes,status) VALUES(?,?,?,?,?,?,'approved')",
                    recordId,owner,q.get("shift_id"),parse((String)q.get("proposed_in_at")),parse((String)q.get("proposed_out_at")),q.get("proposed_break_minutes"));
            } else {
                if(before==null || before.get("clock_out_at")==null) throw error(HttpStatus.CONFLICT,"Công gốc đã thay đổi");
                if(q.get("base_record_version")==null || !q.get("base_record_version").equals(before.get("version")))
                    throw error(HttpStatus.CONFLICT,"Công gốc đã đổi sau khi gửi yêu cầu. Hãy gửi lại yêu cầu mới.");
                int version=(Integer)before.get("version");
                int changed=db.update("""
                    UPDATE attendance_records SET clock_in_at=?,clock_out_at=?,break_minutes=?,status='approved',version=version+1,updated_at=SYSUTCDATETIME()
                    WHERE id=? AND version=?
                    """,parse((String)q.get("proposed_in_at")),parse((String)q.get("proposed_out_at")),q.get("proposed_break_minutes"),recordId,version);
                if(changed!=1) throw error(HttpStatus.CONFLICT,"Công gốc đã được thay đổi đồng thời");
            }
            after=record(recordId);
        }
        db.update("""
            UPDATE attendance_adjustment_requests SET status=?,resolved_at=SYSUTCDATETIME(),resolved_by=?,resolution_reason=? WHERE id=? AND status='pending'
            """,review.approve()?"approved":"rejected",actor,review.reason().trim(),requestId);
        audit(recordId,requestId,owner,actor,review.approve()?"APPROVE_ADJUSTMENT":"REJECT_ADJUSTMENT",before,after,review.reason());
        return one("SELECT id,user_id,shift_id,status,resolved_by,resolved_at,resolution_reason FROM attendance_adjustment_requests WHERE id=?",requestId);
    }
    @Transactional
    public Map<String,Object> lockPeriod(String actor,String periodId,String reason) {
        needAdmin(actor);
        if(reason==null || reason.trim().length()<5) throw error(HttpStatus.BAD_REQUEST,"Lý do khóa kỳ cần ít nhất 5 ký tự");
        if(one("SELECT id FROM periods WHERE id=?",periodId)==null) throw error(HttpStatus.NOT_FOUND,"Không tìm thấy kỳ");
        if(one("SELECT period_id FROM attendance_period_locks WHERE period_id=?",periodId)!=null)
            throw error(HttpStatus.CONFLICT,"Kỳ công đã khóa");
        db.update("INSERT INTO attendance_period_locks(period_id,locked_by,reason) VALUES(?,?,?)",periodId,actor,reason.trim());
        audit(null,null,actor,actor,"LOCK_PERIOD",null,Map.of("periodId",periodId),reason);
        return one("SELECT period_id,locked_by,locked_at,reason FROM attendance_period_locks WHERE period_id=?",periodId);
    }
    public List<Map<String,Object>> auditHistory(String actor,String userId){
        boolean admin=isAdmin(actor);
        String target=(userId==null||userId.isBlank())?actor:userId;
        if(!admin && !actor.equals(target)) throw error(HttpStatus.FORBIDDEN,"Không có quyền xem lịch sử của người khác");
        return query("SELECT TOP 200 id,record_id,request_id,user_id,actor_id,action,before_json,after_json,reason,occurred_at FROM attendance_audit WHERE user_id=? ORDER BY occurred_at DESC",target);
    }
}
