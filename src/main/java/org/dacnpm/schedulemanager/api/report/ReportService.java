package org.dacnpm.schedulemanager.api.report;

import org.dacnpm.schedulemanager.api.attendance.AttendanceService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Profile("sqlserver")
@Service
public class ReportService {
    private final JdbcTemplate db;
    private final AttendanceService attendance;
    public ReportService(JdbcTemplate db, AttendanceService attendance){this.db=db;this.attendance=attendance;}

    public record Period(String id,String name,LocalDate start,LocalDate end,String status,String deadline){}
    public Period period(String id) {
        var rows=db.query("SELECT id,name,start_date,end_date,status,deadline FROM periods WHERE id=?",
          (r,n)->new Period(r.getString(1),r.getString(2),r.getDate(3).toLocalDate(),r.getDate(4).toLocalDate(),r.getString(5),
            r.getTimestamp(6)==null?null:r.getTimestamp(6).toLocalDateTime().toString()),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Chưa có kỳ trong SQL Server. Hãy tạo/nhập kỳ từ API trước khi xem báo cáo.");
        return rows.get(0);
    }
    public List<Map<String,Object>> periods(String actor) {
        attendance.isAdmin(actor);
        return db.query("SELECT id,name,start_date,end_date,status,deadline FROM periods ORDER BY start_date DESC",(r,n)->{
           Map<String,Object> item=new LinkedHashMap<>();
           item.put("id",r.getString(1));item.put("name",r.getString(2));
           item.put("startDate",r.getDate(3).toString());item.put("endDate",r.getDate(4).toString());
           item.put("status",r.getString(5));item.put("deadline",r.getTimestamp(6)==null?null:r.getTimestamp(6).toLocalDateTime().toString());
           return item;
        });
    }
    public record UserHours(String userId,String name,String employeeCode,BigDecimal targetHours,
                            BigDecimal scheduledHours,BigDecimal actualHours,BigDecimal approvedHours,
                            BigDecimal differenceHours,Integer percent,String progressLabel){}
    public static Integer ratio(BigDecimal scheduled,BigDecimal target) {
        if(target==null||target.signum()<=0)return null;
        return scheduled.multiply(new BigDecimal("100")).divide(target,0,java.math.RoundingMode.HALF_UP).intValueExact();
    }
    public static String progressLabel(BigDecimal scheduled,BigDecimal target) {
        if(target==null||target.signum()<=0)return "Chưa đủ dữ liệu";
        BigDecimal delta=scheduled.subtract(target);
        if(delta.signum()>0)return "Vượt "+delta.stripTrailingZeros().toPlainString()+" giờ";
        if(delta.signum()<0)return "Thiếu "+delta.abs().stripTrailingZeros().toPlainString()+" giờ";
        return "Đủ giờ";
    }
    /** The shared canonical source for report cards, table, chart and CSV/XLSX. */
    public List<UserHours> userHours(String actor,String periodId,String userId) {
        boolean admin=attendance.isAdmin(actor);
        if(!admin && userId!=null && !userId.equals(actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không có quyền xem báo cáo người khác");
        Period p=period(periodId);
        String target=admin?(userId==null||userId.isBlank()?null:userId):actor;
        // SQL hours only from published shifts, not draft/previews. Actual requires clock-out;
        // approved hours requires a completed APPROVED timesheet (never inferred from assignments).
        String sql="""
         SELECT u.id AS uid,u.full_name,u.employee_code,u.target_rate,
           COALESCE((SELECT TOP 1 standard_hours FROM schedule_rules WHERE id='rules_main'),0) AS standard_hours,
           COALESCE((SELECT SUM(CASE WHEN s.end_time<=s.start_time
                 THEN DATEDIFF(MINUTE,s.start_time,s.end_time)+1440
                 ELSE DATEDIFF(MINUTE,s.start_time,s.end_time) END)
             FROM assignments a JOIN shifts s ON s.id=a.shift_id
             WHERE a.user_id=u.id AND a.status='confirmed' AND s.status='published'
             AND s.shift_date BETWEEN ? AND ?),0) AS scheduled_minutes,
           COALESCE((SELECT SUM(DATEDIFF(MINUTE,r.clock_in_at,r.clock_out_at)-r.break_minutes)
             FROM attendance_records r JOIN shifts s ON s.id=r.shift_id
             WHERE r.user_id=u.id AND r.clock_out_at IS NOT NULL
             AND s.shift_date BETWEEN ? AND ?),0) AS actual_minutes,
           COALESCE((SELECT SUM(DATEDIFF(MINUTE,r.clock_in_at,r.clock_out_at)-r.break_minutes)
             FROM attendance_records r JOIN shifts s ON s.id=r.shift_id
             WHERE r.user_id=u.id AND r.status='approved'
             AND s.shift_date BETWEEN ? AND ?),0) AS approved_minutes
         FROM users u WHERE u.role='user' AND u.status='active' AND (? IS NULL OR u.id=?)
         ORDER BY u.full_name,u.id
        """;
        var rows=db.query(sql,(rs,n)->{
            BigDecimal standard=rs.getBigDecimal("standard_hours");
            BigDecimal rate=rs.getBigDecimal("target_rate");
            BigDecimal targetHours=standard.multiply(rate).divide(BigDecimal.valueOf(100),2,java.math.RoundingMode.HALF_UP);
            BigDecimal scheduled=BigDecimal.valueOf(rs.getLong("scheduled_minutes")).divide(BigDecimal.valueOf(60),2,java.math.RoundingMode.HALF_UP);
            BigDecimal actual=BigDecimal.valueOf(rs.getLong("actual_minutes")).divide(BigDecimal.valueOf(60),2,java.math.RoundingMode.HALF_UP);
            BigDecimal approved=BigDecimal.valueOf(rs.getLong("approved_minutes")).divide(BigDecimal.valueOf(60),2,java.math.RoundingMode.HALF_UP);
            return new UserHours(rs.getString("uid"),rs.getString("full_name"),rs.getString("employee_code"),targetHours,scheduled,actual,approved,
                scheduled.subtract(targetHours),ratio(scheduled,targetHours),progressLabel(scheduled,targetHours));
        },Date.valueOf(p.start),Date.valueOf(p.end),Date.valueOf(p.start),Date.valueOf(p.end),
          Date.valueOf(p.start),Date.valueOf(p.end),target,target);
        return rows;
    }
    public Map<String,Object> dashboard(String actor,String periodId,String userId){
        boolean admin=attendance.isAdmin(actor);
        Period p=period(periodId);
        List<UserHours> people=userHours(actor,periodId,userId);
        BigDecimal target=people.stream().map(UserHours::targetHours).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal scheduled=people.stream().map(UserHours::scheduledHours).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal actual=people.stream().map(UserHours::actualHours).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal approved=people.stream().map(UserHours::approvedHours).reduce(BigDecimal.ZERO,BigDecimal::add);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("periodId",p.id());out.put("periodName",p.name());out.put("periodStatus",p.status());out.put("deadline",p.deadline());
        out.put("generatedAt",Instant.now().toString());out.put("source","sqlserver");out.put("variant","published-only");
        out.put("employeeCount",people.size());out.put("targetHours",target);out.put("scheduledHours",scheduled);
        out.put("actualHours",actual);out.put("approvedHours",approved);out.put("differenceHours",scheduled.subtract(target));
        out.put("percentage",ratio(scheduled,target));out.put("progressLabel",progressLabel(scheduled,target));
        if(admin){
            Integer shifts=db.queryForObject("SELECT COUNT(*) FROM shifts WHERE shift_date BETWEEN ? AND ?",Integer.class,Date.valueOf(p.start()),Date.valueOf(p.end()));
            Integer published=db.queryForObject("SELECT COUNT(*) FROM shifts WHERE status='published' AND shift_date BETWEEN ? AND ?",Integer.class,Date.valueOf(p.start()),Date.valueOf(p.end()));
            Integer understaffed=db.queryForObject("""
              SELECT COUNT(*) FROM shifts s WHERE s.status='published' AND s.shift_date BETWEEN ? AND ?
              AND (SELECT COUNT(*) FROM assignments a WHERE a.shift_id=s.id AND a.status='confirmed') < s.total_required
              """,Integer.class,Date.valueOf(p.start()),Date.valueOf(p.end()));
            Integer pending=db.queryForObject("""
              SELECT COUNT(*) FROM attendance_adjustment_requests r JOIN shifts s ON s.id=r.shift_id
              WHERE r.status='pending' AND s.shift_date BETWEEN ? AND ?
              """,Integer.class,Date.valueOf(p.start()),Date.valueOf(p.end()));
            Integer working=db.queryForObject("SELECT COUNT(*) FROM attendance_records r JOIN shifts s ON s.id=r.shift_id WHERE r.clock_out_at IS NULL AND s.shift_date BETWEEN ? AND ?",Integer.class,Date.valueOf(p.start()),Date.valueOf(p.end()));
            out.put("totalShifts",shifts);out.put("publishedShifts",published);out.put("understaffedShifts",understaffed);
            out.put("pendingRequests",pending);out.put("currentlyWorking",working);
            out.put("unpublishedShifts",shifts-published);
            // Today's published shifts and missing punch-ins, scoped to current day in policy timezone.
            String zone=db.queryForObject("SELECT timezone_name FROM attendance_policies WHERE id='default'",String.class);
            LocalDate today=LocalDate.now(ZoneId.of(zone));
            Integer todayShifts=db.queryForObject("SELECT COUNT(*) FROM shifts WHERE shift_date=? AND status='published'",Integer.class,Date.valueOf(today));
            int missingCheckin=(int)attendanceStatus(actor,periodId).stream().filter(row ->
              today.toString().equals(row.get("date")) &&
              ("Chưa check-in".equals(row.get("status")) || "Vắng".equals(row.get("status")))).count();
            out.put("todayShifts",todayShifts);out.put("notCheckedInToday",missingCheckin);
        }
        return out;
    }
    public List<Map<String,Object>> attendanceStatus(String actor,String periodId) {
        boolean admin=attendance.isAdmin(actor);
        Period p=period(periodId);
        String zoneName=db.queryForObject("SELECT timezone_name FROM attendance_policies WHERE id='default'",String.class);
        int grace=db.queryForObject("SELECT late_grace_minutes FROM attendance_policies WHERE id='default'",Integer.class);
        ZoneId zone=ZoneId.of(zoneName);
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        List<Map<String,Object>> result=new ArrayList<>();
        db.query("""
          SELECT u.id AS user_id,u.full_name,s.id AS shift_id,s.name,s.shift_date,s.start_time,s.end_time,
            r.clock_in_at,r.clock_out_at,r.break_minutes
          FROM assignments a JOIN users u ON u.id=a.user_id
          JOIN shifts s ON s.id=a.shift_id
          LEFT JOIN attendance_records r ON r.shift_id=s.id AND r.user_id=u.id
          WHERE a.status='confirmed' AND s.status='published' AND u.status='active'
            AND s.shift_date BETWEEN ? AND ? AND (?=1 OR u.id=?)
          ORDER BY s.shift_date DESC,s.start_time,u.full_name
          """, rs -> {
            String name=rs.getString("full_name");String userId=rs.getString("user_id");
            String shiftName=rs.getString("name");String shiftId=rs.getString("shift_id");
            LocalDate date=rs.getDate("shift_date").toLocalDate();
            LocalTime from=rs.getTime("start_time").toLocalTime(),to=rs.getTime("end_time").toLocalTime();
            LocalDateTime plannedStart=LocalDateTime.ofInstant(ZonedDateTime.of(date,from,zone).toInstant(),ZoneOffset.UTC);
            LocalDateTime plannedEnd=LocalDateTime.ofInstant(ZonedDateTime.of(to.compareTo(from)<=0?date.plusDays(1):date,to,zone).toInstant(),ZoneOffset.UTC);
            var in=rs.getTimestamp("clock_in_at");var out=rs.getTimestamp("clock_out_at");
            if(now.isBefore(plannedStart.plusMinutes(grace)))return;
            String state=null;long late=0;
            if(in==null)state=now.isAfter(plannedEnd)?"Vắng":"Chưa check-in";
            else {
              LocalDateTime checkedIn=in.toLocalDateTime();
              late=org.dacnpm.schedulemanager.api.attendance.AttendanceMath.lateMinutes(checkedIn,plannedStart,grace);
              if(out==null&&now.isAfter(plannedEnd.plusMinutes(30)))state="Thiếu check-out";
              else if(late>0)state="Đi muộn";
              else if(out!=null) {
                long overtime=org.dacnpm.schedulemanager.api.attendance.AttendanceMath.overtimeMinutes(
                   checkedIn,out.toLocalDateTime(),rs.getInt("break_minutes"),plannedStart,plannedEnd);
                if(overtime>0)state="Tăng ca ("+overtime+" phút)";
              }
            }
            if(state!=null){Map<String,Object> row=new LinkedHashMap<>();
              row.put("userId",userId);row.put("name",name);row.put("shiftId",shiftId);
              row.put("shiftName",shiftName);row.put("date",date.toString());row.put("status",state);row.put("lateMinutes",late);
              result.add(row);
            }
          },Date.valueOf(p.start()),Date.valueOf(p.end()),admin?1:0,actor);
        return result;
    }
    public List<Map<String,Object>> shortages(String actor,String periodId){
        if(!attendance.isAdmin(actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Chỉ quản trị viên");
        Period p=period(periodId);
        return db.query("""
         SELECT s.id,s.name,s.shift_date,s.total_required,
           (SELECT COUNT(*) FROM assignments a WHERE a.shift_id=s.id AND a.status='confirmed') AS assigned,
           s.note FROM shifts s WHERE s.status='published' AND s.shift_date BETWEEN ? AND ?
           AND (SELECT COUNT(*) FROM assignments a WHERE a.shift_id=s.id AND a.status='confirmed') < s.total_required
         ORDER BY s.shift_date,s.start_time
        """,(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("shiftId",rs.getString(1));row.put("name",rs.getString(2));row.put("date",rs.getDate(3).toString());
            row.put("required",rs.getInt(4));row.put("assigned",rs.getInt(5));
            row.put("reason",rs.getString(6)==null?"Chưa ghi lý do":rs.getString(6));return row;
        },Date.valueOf(p.start()),Date.valueOf(p.end()));
    }
    public byte[] csv(List<UserHours> people) {
        StringBuilder sb=new StringBuilder("\ufeffMã NV,Họ tên,Mục tiêu (h),Đã xếp công bố (h),Giờ thực tế (h),Giờ duyệt (h),Chênh lệch (h),Tỷ lệ (%),Trạng thái\r\n");
        for(var u:people){
            Object[] cells={u.employeeCode(),u.name(),u.targetHours(),u.scheduledHours(),u.actualHours(),u.approvedHours(),u.differenceHours(),u.percent(),u.progressLabel()};
            for(int i=0;i<cells.length;i++){if(i>0)sb.append(',');sb.append(csvCell(cells[i]));}sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
    private String csvCell(Object o){
        if(o==null)return "";
        String s=String.valueOf(o);
        // Excel CSV formula injection guard for untrusted staff names/codes
        if(!s.isEmpty()&&"=+-@\t\r".indexOf(s.charAt(0))>=0)s="'"+s;
        return '"'+s.replace("\"","\"\"")+'"';
    }
    private String xml(Object o){if(o==null)return "";return String.valueOf(o).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    /** Minimal valid XLSX, generated with JDK zip/XML; no additional dependency. */
    public byte[] xlsx(List<UserHours> people) {
        String[] heads={"Mã NV","Họ tên","Mục tiêu (h)","Đã xếp công bố (h)","Giờ thực tế (h)","Giờ duyệt (h)","Chênh lệch (h)","Tỷ lệ (%)","Trạng thái"};
        List<Object[]> rows=new ArrayList<>();rows.add(heads);
        for(var u:people)rows.add(new Object[]{u.employeeCode(),u.name(),u.targetHours(),u.scheduledHours(),u.actualHours(),u.approvedHours(),u.differenceHours(),u.percent(),u.progressLabel()});
        StringBuilder sheet=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for(int i=0;i<rows.size();i++){
            sheet.append("<row r=\"").append(i+1).append("\">");Object[] row=rows.get(i);
            for(int j=0;j<row.length;j++){
                Object v=row[j];if(v==null)continue;
                String col=String.valueOf((char)('A'+j));String cell=col+(i+1);
                if(v instanceof Number){sheet.append("<c r=\"").append(cell).append("\"><v>").append(xml(v)).append("</v></c>");}
                else sheet.append("<c r=\"").append(cell).append("\" t=\"inlineStr\"><is><t>").append(xml(v)).append("</t></is></c>");
            }sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        Map<String,String> entries=new LinkedHashMap<>();
        entries.put("[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        entries.put("_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        entries.put("xl/workbook.xml","<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Bao cao\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        entries.put("xl/_rels/workbook.xml.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        entries.put("xl/worksheets/sheet1.xml",sheet.toString());
        try(ByteArrayOutputStream buf=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(buf)) {
            for(var entry:entries.entrySet()){
                zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }zip.finish();return buf.toByteArray();
        }catch(IOException ex){throw new IllegalStateException(ex);}
    }
}
