package org.dacnpm.schedulemanager;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.dacnpm.schedulemanager.api.attendance.AttendanceService;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels;
import org.dacnpm.schedulemanager.api.availability.AvailabilityService;
import org.dacnpm.schedulemanager.api.periods.*;
import org.dacnpm.schedulemanager.api.requests.ShiftRequestService;
import org.dacnpm.schedulemanager.api.openshifts.OpenShiftService;
import org.dacnpm.schedulemanager.api.leave.LeaveService;
import org.dacnpm.schedulemanager.common.*;
import org.dacnpm.schedulemanager.domain.events.*;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentValidationService;
import org.dacnpm.schedulemanager.schedule.plan.*;
import org.dacnpm.schedulemanager.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Real SQL Server migrations and cross-module behavior. Every fixture is rolled back. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"sqlserver","test"})
@EnabledIfEnvironmentVariable(named="SM_RUN_SQL_TESTS",matches="true")
@Transactional
class IntegratedMocAIT {
    static final CurrentUser ADMIN=new CurrentUser("usr_admin_001",true);
    static final CurrentUser A=new CurrentUser("moc_it_a",false),B=new CurrentUser("moc_it_b",false);
    static final String PERIOD="moc_it_period", S1="moc_it_s1",S2="moc_it_s2";
    static final LocalDate START=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(5);

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        Properties p=new Properties();try(var reader=Files.newBufferedReader(Path.of(".local-test/application-testdb.properties"))){p.load(reader);}
        if(!p.getProperty("spring.datasource.url","").contains("databaseName=ScheduleManager_MocA_Test_"))
            throw new IllegalStateException("Integration tests require the isolated MocA test database");
        for(String key:List.of("spring.datasource.url","spring.datasource.username","spring.datasource.password"))registry.add(key,()->p.getProperty(key));
        registry.add("spring.flyway.enabled",()->"true");registry.add("spring.config.import",()->"");
        registry.add("spring.datasource.driver-class-name",()->"com.microsoft.sqlserver.jdbc.SQLServerDriver");
        registry.add("app.scheduling.enabled",()->"false");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanService plans;
    @Autowired PlanWorkflowService workflow;
    @Autowired PeriodService periods;
    @Autowired AvailabilityService availability;
    @Autowired AssignmentValidationService validator;
    @Autowired ShiftRequestService requests;
    @Autowired OpenShiftService openShifts;
    @Autowired LeaveService leave;
    @Autowired DomainEventPublisher events;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired AttendanceService attendance;
    @Autowired org.dacnpm.schedulemanager.schedule.report.ReportService scheduleReports;
    @Autowired org.dacnpm.schedulemanager.api.report.ReportService reports;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;

    @BeforeEach void fixture(org.junit.jupiter.api.TestInfo test) {
        String method=test.getTestMethod().orElseThrow().getName();
        // Independent outbox transactions must not scan users inserted by an uncommitted fixture transaction.
        if(method.equals("outboxDeliveryCanRecoverFromFailureWithoutDuplicatingNotifications")
                ||method.equals("aRolledBackBusinessTransactionCannotLeaveAnOutboxEvent"))return;
        for(String user:List.of(A.id(),B.id()))jdbc.update("INSERT INTO users(id,employee_code,username,password_hash,full_name,role,level_id) SELECT ?,?,?,password_hash,?,'user',NULL FROM users WHERE id=?",
                user,user,user,user,ADMIN.id());
        jdbc.update("INSERT INTO periods(id,name,cycle,start_date,end_date,status,deadline) VALUES(?,N'Kỳ IT','week',?,?,'locked',?)",PERIOD,START,START.plusDays(6),LocalDateTime.ofInstant(Instant.now().minusSeconds(3600),ZoneOffset.UTC));
        jdbc.update("INSERT INTO rule_changes(id,status,base_revision,reason,items_json,created_by) VALUES('moc_it_change','applied',0,N'Fixture IT','[]',?)",ADMIN.id());
        Map<String,String> values=Map.of("target_hours","60","max_hours_mode","percent","max_hours_percent","125","max_hours_per_day","12","max_hours_per_week","80","min_rest_hours","8","max_consecutive_days","6");
        int n=0;for(String user:List.of(A.id(),B.id()))for(var setting:values.entrySet())jdbc.update("INSERT INTO rule_settings(id,param_key,scope_type,scope_id,value_text,effective_from,change_id) VALUES(?,?,'user',?,?,?,'moc_it_change')",
            "moc_it_rule"+(++n),setting.getKey(),user,setting.getValue(),START.minusDays(30));
        for(String user:List.of(A.id(),B.id())){
            jdbc.update("INSERT INTO period_applicable_users(period_id,user_id) VALUES(?,?)",PERIOD,user);
            jdbc.update("INSERT INTO availability_registrations(id,user_id,period_id,status) VALUES(?,?,?,'submitted')","moc_it_reg"+user,user,PERIOD);
            for(LocalDate date:List.of(START,START.plusDays(1),START.plusDays(6),START.plusDays(7))){
                jdbc.update("INSERT INTO availability_entries(registration_id,available_date) VALUES(?,?)","moc_it_reg"+user,date);
                jdbc.update("INSERT INTO availability_slots(entry_id,start_time,end_time,slot_type) SELECT id,'00:00','23:59','available' FROM availability_entries WHERE registration_id=? AND available_date=?","moc_it_reg"+user,date);
            }
        }
        shift(S1,START,"08:00","12:00",30);shift(S2,START.plusDays(1),"08:00","12:00",0);
    }
    void shift(String id,LocalDate date,String from,String to,int rest){
        jdbc.update("INSERT INTO shifts(id,name,shift_date,start_time,end_time,assignment_mode,total_required,status,break_minutes,period_id) VALUES(?,?,?,CAST(? AS time),CAST(? AS time),'combined',1,'published',?,?)",id,id,date,from,to,rest,!date.isBefore(START)&&!date.isAfter(START.plusDays(6))?PERIOD:null);
    }
    void assign(String id,String shift,String user){jdbc.update("INSERT INTO assignments(id,shift_id,user_id,status,assigned_by) VALUES(?,?,?,'confirmed',?)",id,shift,user,ADMIN.id());}

    @Test void unavailableIntervalsAreHardFailuresThroughTheActualRepository() {
        jdbc.update("INSERT INTO availability_slots(entry_id,start_time,end_time,slot_type) SELECT id,'09:00','10:00','unavailable' FROM availability_entries WHERE registration_id=? AND available_date=?","moc_it_reg"+A.id(),START);
        assertThat(validator.validate(A.id(),S1,null,Set.of(),null)).anyMatch(v->v.code().equals("UNAVAILABLE_INTERVAL")&&v.blocking());
        var dry=plans.dryRun(PERIOD,new PlanService.PlanOptions(List.of(S1),false));
        assertThat(dry.result().assignments()).allMatch(a->a.userId().equals(B.id()));
    }
    @Test void overlappingPeriodsDoNotMixTheirShiftsOrEmployeeScopes() {
        jdbc.update("UPDATE shifts SET status='not_scheduled' WHERE id IN (?,?)",S1,S2);
        jdbc.update("INSERT INTO periods(id,name,cycle,start_date,end_date,status) VALUES('moc_other_period',N'Kỳ khác','weekly',?,?,'locked')",START,START.plusDays(6));
        jdbc.update("INSERT INTO period_applicable_users(period_id,user_id) VALUES('moc_other_period',?)",B.id());
        shift("moc_other_shift",START,"15:00","19:00",0);
        jdbc.update("UPDATE shifts SET period_id='moc_other_period' WHERE id='moc_other_shift'");
        var dry=plans.dryRun(PERIOD,new PlanService.PlanOptions(List.of(),false));
        assertThat(dry.shiftIds()).containsExactlyInAnyOrder(S1,S2);
        assertThat(validator.validate(A.id(),S1,null,Set.of(),null)).isEmpty();
        assertThatThrownBy(()->plans.dryRun(PERIOD,new PlanService.PlanOptions(List.of("moc_other_shift"),false)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void publishIsVersionedAndProducesDurableDeduplicatedNotifications() {
        var plan=plans.create(PERIOD,"Phương án IT",new PlanService.PlanOptions(List.of(S1,S2),false),ADMIN.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assignments WHERE shift_id IN (?,?)",Integer.class,S1,S2)).isZero();
        var submitted=workflow.submit(plan.id(),plan.version(),ADMIN.id()).plan();
        var approved=workflow.approve(plan.id(),submitted.version(),null,ADMIN.id()).plan();
        workflow.publish(plan.id(),approved.version(),false,ADMIN.id());
        String eventId="schedule.published:"+plan.id()+":v"+(approved.version()+1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_events WHERE id=?",Integer.class,eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM history WHERE event_type='plan_publish' AND detail LIKE ? AND before_json IS NOT NULL AND after_json IS NOT NULL",Integer.class,plan.id()+"%" )).isEqualTo(1);
        assertThat(dispatcher.dispatchOne(eventId)).isTrue();
        assertThat(dispatcher.dispatchOne(eventId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id=?",Integer.class,eventId)).isPositive();
        assertThatThrownBy(()->workflow.publish(plan.id(),approved.version(),false,ADMIN.id())).isInstanceOf(RuntimeException.class);
    }
    @Test void swappingTwoAssignmentsUsesTheOfficialValidatorAndCanBeApprovedOnlyOnce() {
        assign("moc_it_as1",S1,A.id());assign("moc_it_as2",S2,B.id());
        var request=requests.create(A,new ShiftRequestService.CreateRequest("swap","moc_it_as1",B.id(),"moc_it_as2","Đổi ca kiểm thử"));
        var accepted=requests.peerRespond(B,request.id(),true,request.version(),null);
        var approved=requests.approve(ADMIN,request.id(),accepted.version(),null);
        requests.approve(ADMIN,request.id(),approved.version(),null);
        assertThat(jdbc.queryForList("SELECT user_id FROM assignments WHERE shift_id=? AND status='confirmed'",String.class,S1)).containsExactly(B.id());
        assertThat(jdbc.queryForList("SELECT user_id FROM assignments WHERE shift_id=? AND status='confirmed'",String.class,S2)).containsExactly(A.id());
    }
    @Test void aChangedScheduleCannotBeApprovedUsingAnOlderRequest() {
        assign("moc_it_as1",S1,A.id());
        var request=requests.create(A,new ShiftRequestService.CreateRequest("cover","moc_it_as1",B.id(),null,"Nhờ làm thay kiểm thử"));
        var accepted=requests.peerRespond(B,request.id(),true,request.version(),null);
        jdbc.update("UPDATE shifts SET version=version+1 WHERE id=?",S1);
        assertThatThrownBy(()->requests.approve(ADMIN,request.id(),accepted.version(),null))
            .isInstanceOf(ApiException.class).satisfies(error->assertThat(((ApiException)error).getCode()).isEqualTo("SCHEDULE_CHANGED"));
    }
    @Test void aRolledBackBusinessTransactionCannotLeaveAnOutboxEvent() {
        String id="rollback_"+UUID.randomUUID();
        var independent=new TransactionTemplate(tx.getTransactionManager());
        independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(status->{
            events.publish(new DomainEvent(id,"test.rollback","test",id,1,ADMIN.id(),List.of(ADMIN.id()),"Test","Test","calendar"));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_events WHERE id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id=?",Integer.class,id)).isZero();
    }
    @Test void outboxDeliveryCanRecoverFromFailureWithoutDuplicatingNotifications() {
        String id="retry_"+UUID.randomUUID();
        var independent=new TransactionTemplate(tx.getTransactionManager());
        independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var suspended=new TransactionTemplate(tx.getTransactionManager());
        suspended.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        try {
            independent.executeWithoutResult(status->{
                events.publish(new DomainEvent(id,"test.retry","test",id,1,ADMIN.id(),List.of(ADMIN.id()),"Test","Test","calendar"));
                jdbc.update("UPDATE domain_events SET recipients_json='{}' WHERE id=?",id);
            });
            suspended.executeWithoutResult(status->{
                assertThat(dispatcher.dispatchOne(id)).isFalse();
                assertThat(jdbc.queryForObject("SELECT status FROM domain_events WHERE id=?",String.class,id)).isEqualTo("failed");
                jdbc.update("UPDATE domain_events SET recipients_json=?,next_attempt_at=SYSUTCDATETIME() WHERE id=?","[\""+ADMIN.id()+"\"]",id);
                assertThat(dispatcher.dispatchOne(id)).isTrue();
                assertThat(dispatcher.dispatchOne(id)).isFalse();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id=?",Integer.class,id)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT attempts FROM domain_events WHERE id=?",Integer.class,id)).isEqualTo(2);
            });
        } finally {
            independent.executeWithoutResult(status->{jdbc.update("DELETE FROM notifications WHERE event_id=?",id);jdbc.update("DELETE FROM domain_events WHERE id=?",id);});
        }
    }
    @Test void openShiftNeverAllocatesTheLastPlaceTwice() {
        var open=openShifts.create(ADMIN,new OpenShiftService.CreateOpenShift(S1,1,null,START.minusDays(1).atTime(20,0),"Kiểm thử"));
        var ca=openShifts.claim(A,open.id(),null);var cb=openShifts.claim(B,open.id(),null);
        openShifts.approveClaim(ADMIN,open.id(),ca.id(),new OpenShiftService.Decision(ca.version(),null));
        assertThatThrownBy(()->openShifts.approveClaim(ADMIN,open.id(),cb.id(),new OpenShiftService.Decision(cb.version(),null))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT filled_count FROM open_shifts WHERE id=?",Integer.class,open.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assignments WHERE shift_id=? AND status='confirmed'",Integer.class,S1)).isEqualTo(1);
    }
    @Test void approvedLeaveRemovesTheAssignmentAndPreventsReschedulingOnThatDay() {
        assign("moc_it_as1",S1,A.id());
        String type=jdbc.queryForList("SELECT id FROM leave_types WHERE active=1 AND requires_document=0 AND min_notice_days=0",String.class).get(0);
        var request=leave.create(A,new LeaveService.CreateLeave(type,START,START,"Nghỉ kiểm thử",null));
        leave.approve(ADMIN,request.id(),request.version(),null);
        assertThat(jdbc.queryForObject("SELECT status FROM assignments WHERE id='moc_it_as1'",String.class)).isEqualTo("cancelled");
        assertThat(validator.validate(A.id(),S1,null,Set.of(),null)).anyMatch(v->v.code().equals("UNAVAILABLE_INTERVAL"));
    }
    @Test void scheduleAndAttendanceReportsAgreeForBreaksAndOvernightPeriodBoundaries() {
        shift("moc_it_night",START.plusDays(6),"22:00","06:00",60);
        assign("moc_it_as1",S1,A.id());assign("moc_it_night_as","moc_it_night",A.id());
        var canonical=scheduleReports.hours(PERIOD,"published",null,new org.dacnpm.schedulemanager.schedule.report.ReportService.Filters(A.id(),null,null));
        var row=reports.userHours(ADMIN.id(),PERIOD,A.id()).get(0);
        assertThat(row.scheduledHours().doubleValue()).isEqualTo(canonical.employees().get(0).hours()).isEqualTo(5.25);
        assertThat(row.targetHours().doubleValue()).isEqualTo(60);
    }
    @Test void anOvernightBoundaryShiftCanUseSubmittedAvailabilityFromTheAdjacentPeriod() {
        LocalDate next=START.plusDays(7);
        jdbc.update("DELETE FROM availability_entries WHERE registration_id=? AND available_date=?","moc_it_reg"+A.id(),next);
        shift("moc_it_boundary",START.plusDays(6),"22:00","06:00",60);
        assertThat(validator.validate(A.id(),"moc_it_boundary",null,Set.of(),null)).isNotEmpty();
        jdbc.update("INSERT INTO periods(id,name,cycle,start_date,end_date,status) VALUES('moc_next_period',N'Kỳ tiếp','weekly',?,?,'open')",next,next.plusDays(6));
        jdbc.update("INSERT INTO availability_registrations(id,user_id,period_id,status) VALUES('moc_next_registration',?,'moc_next_period','submitted')",A.id());
        jdbc.update("INSERT INTO availability_entries(registration_id,available_date) VALUES('moc_next_registration',?)",next);
        jdbc.update("INSERT INTO availability_slots(entry_id,start_time,end_time,slot_type) SELECT id,'00:00','23:59','available' FROM availability_entries WHERE registration_id='moc_next_registration'");
        assertThat(validator.validate(A.id(),"moc_it_boundary",null,Set.of(),null)).isEmpty();
    }
    @Test void archiveCannotHideAnUnfinishedSchedulePlan() {
        plans.create(PERIOD,"Nháp IT",new PlanService.PlanOptions(List.of(S1),false),ADMIN.id());
        assertThatThrownBy(()->periods.archive(ADMIN,PERIOD,PeriodActionRequest.empty())).isInstanceOf(ApiException.class);
    }
    @Test void oldFormsAreRejectedAfterTheServerDeadline() {
        jdbc.update("UPDATE periods SET status='open' WHERE id=?",PERIOD);
        assertThatThrownBy(()->availability.submitMine(A,PERIOD,new AvailabilityModels.SaveRequest(List.of())))
                .isInstanceOf(ApiException.class).satisfies(error->assertThat(((ApiException)error).getCode()).isEqualTo("REGISTRATION_CLOSED"));
    }
    @Test void employeeCannotReadAnotherEmployeesReportOrManageSchedules() throws Exception {
        String token=jwt.issue(A.id(),A.id(),"user");
        mvc.perform(get("/api/schedule/plans").param("periodId",PERIOD).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/users").param("periodId",PERIOD).param("userId",B.id()).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void publicWorkspaceAllowsOnlySameOriginFramesAndKeepsPrivateFilesProtected() throws Exception {
        mvc.perform(get("/attendance.html")).andExpect(status().isOk()).andExpect(header().string("X-Frame-Options","SAMEORIGIN"));
        mvc.perform(get("/.env")).andExpect(status().isUnauthorized());
    }
    @Test void attendanceRejectsDuplicateCheckinAndStaleAdjustmentRequests() {
        var local=LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        var from=local.minusHours(1);var to=local.plusHours(2);
        shift("moc_it_now",from.toLocalDate(),from.toLocalTime().withSecond(0).withNano(0).toString(),to.toLocalTime().withSecond(0).withNano(0).toString(),0);
        assign("moc_it_now_as","moc_it_now",A.id());
        var record=attendance.checkIn(A.id(),"moc_it_now");
        assertThatThrownBy(()->attendance.checkIn(A.id(),"moc_it_now")).isInstanceOf(RuntimeException.class);
        String recordId=(String)record.get("id");
        Instant now=Instant.now();jdbc.update("UPDATE attendance_records SET clock_in_at=? WHERE id=?",LocalDateTime.ofInstant(now.minusSeconds(600),ZoneOffset.UTC),recordId);
        attendance.checkOut(A.id(),recordId,0);
        var request=attendance.request(A.id(),new AttendanceService.Change(null,recordId,now.minusSeconds(600).toString(),now.toString(),0,"Sửa công kiểm thử"));
        jdbc.update("UPDATE attendance_records SET version=version+1 WHERE id=?",recordId);
        assertThatThrownBy(()->attendance.review(ADMIN.id(),(String)request.get("id"),new AttendanceService.Review(true,"Duyệt kiểm thử")))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("đã đổi");
    }
}
