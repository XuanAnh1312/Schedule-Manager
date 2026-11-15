package org.dacnpm.schedulemanager.api.leave;

import org.dacnpm.schedulemanager.api.approvals.ApprovalFlow;
import org.dacnpm.schedulemanager.api.approvals.ApprovalFlowService;
import org.dacnpm.schedulemanager.api.approvals.FakeApprovalRepository;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentInfo;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.ShiftInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BAO-04: đơn nghỉ duyệt theo bước; lịch và số dư chỉ đổi ở bước cuối; báo cáo vắng theo quyền. */
class LeaveApprovalStepsTest {

    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final CurrentUser LAN = new CurrentUser("mgr_lan", false);
    static final CurrentUser HUNG = new CurrentUser("mgr_hung", false);
    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final LocalDate D5 = LocalDate.of(2026, 11, 5);

    LeaveRepository leaves;
    AssignmentMutationService schedule;
    DomainEventPublisher events;
    FakeApprovalRepository steps;
    LeaveService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        leaves = mock(LeaveRepository.class);
        schedule = mock(AssignmentMutationService.class);
        events = mock(DomainEventPublisher.class);
        steps = new FakeApprovalRepository();
        steps.managers.put("nv001", "mgr_lan");
        steps.managers.put("nv009", "mgr_hung");
        steps.mode("leave", null, ApprovalFlow.MANAGER_THEN_ADMIN);
        service = new LeaveService(leaves, schedule, events, mock(AuditTrail.class), clock, mock(LeaveBalanceService.class),
                new ApprovalFlowService(steps, mock(AuditTrail.class)));
        when(leaves.findType("leave_annual")).thenReturn(Optional.of(
                new LeaveType("leave_annual", "Nghỉ phép năm", null, true, false, 0, true, true)));
        when(leaves.findType("leave_sick")).thenReturn(Optional.of(
                new LeaveType("leave_sick", "Nghỉ ốm", null, true, true, 0, true, false)));
        when(schedule.findConfirmedAssignments(anyString(), any(), any())).thenReturn(List.of(
                new AssignmentInfo("asg1", "sh1", "nv001", "confirmed", "Ca sáng", D5, LocalTime.of(7, 0),
                        LocalTime.of(15, 0), "published")));
        when(schedule.findShift("sh1")).thenReturn(Optional.of(new ShiftInfo("sh1", "Ca sáng", D5, LocalTime.of(7, 0),
                LocalTime.of(15, 0), "published", 3, 3)));
    }

    static LeaveView leave(String id, String type, String status, String doc) {
        return new LeaveView(id, "nv001", "Trần Văn An", type, "Nghỉ", D5, D5, "Về quê", doc, status, null, null, null,
                null, 0, 0, LocalDateTime.of(2026, 11, 1, 1, 0), false, null);
    }

    @Test
    void create_notifiesTheDirectManager_insteadOfAllAdmins() {
        when(leaves.overlapsActive(anyString(), any(), any())).thenReturn(false);
        when(leaves.findById(anyString())).thenReturn(Optional.of(leave("l1", "leave_annual", "pending", null)));

        service.create(AN, new LeaveService.CreateLeave("leave_annual", D5, D5, "Về quê", null));

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals(List.of("mgr_lan"), event.getValue().recipients());
    }

    @Test
    void managerStep_doesNotTouchTheSchedule_adminStepDoes() {
        new ApprovalFlowService(steps, mock(AuditTrail.class)).start("leave", "l1", "nv001", "leave_annual");
        when(leaves.findById("l1")).thenReturn(Optional.of(leave("l1", "leave_annual", "pending", null)));
        when(leaves.decide(eq("l1"), anyInt(), eq("approved"), any(), any())).thenReturn(1);

        LeaveView afterManager = service.approve(LAN, "l1", 0, "Đồng ý");

        assertEquals("pending", afterManager.status());
        verify(leaves, never()).decide(anyString(), anyInt(), anyString(), any(), any());
        verify(schedule, never()).cancelAssignment(anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals(List.of(DomainEvent.ROLE_ADMIN), event.getValue().recipients());

        service.approve(ADMIN, "l1", 0, null);

        verify(leaves).decide(eq("l1"), eq(0), eq("approved"), eq("admin"), any());
        verify(schedule).cancelAssignment(eq("asg1"), eq("nv001"), eq("admin"), anyString());
    }

    @Test
    void managerOfAnotherTeam_cannotApprove_orSeeTheRequest() {
        new ApprovalFlowService(steps, mock(AuditTrail.class)).start("leave", "l1", "nv001", "leave_annual");
        when(leaves.findById("l1")).thenReturn(Optional.of(leave("l1", "leave_annual", "pending", null)));

        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> service.approve(HUNG, "l1", 0, null)).getCode());
        assertEquals("NOT_FOUND", assertThrows(ApiException.class, () -> service.get(HUNG, "l1")).getCode());
        assertEquals("l1", service.get(LAN, "l1").id());
    }

    @Test
    void requiredDocument_isCheckedAtEveryStep() {
        new ApprovalFlowService(steps, mock(AuditTrail.class)).start("leave", "l2", "nv001", "leave_sick");
        when(leaves.findById("l2")).thenReturn(Optional.of(leave("l2", "leave_sick", "pending", null)));

        ApiException ex = assertThrows(ApiException.class, () -> service.approve(LAN, "l2", 0, null));
        assertEquals("DOCUMENT_REQUIRED", ex.getCode());
        assertTrue(steps.findSteps("leave", "l2").get(0).pending());
    }

    @Test
    void managerCanReject_atTheirStep() {
        new ApprovalFlowService(steps, mock(AuditTrail.class)).start("leave", "l1", "nv001", "leave_annual");
        when(leaves.findById("l1")).thenReturn(Optional.of(leave("l1", "leave_annual", "pending", null)),
                Optional.of(leave("l1", "leave_annual", "rejected", null)));
        when(leaves.decide(eq("l1"), anyInt(), eq("rejected"), any(), any())).thenReturn(1);

        assertEquals("rejected", service.reject(LAN, "l1", 0, "Cuối tháng thiếu người").status());
        assertEquals("skipped", steps.findSteps("leave", "l1").get(1).status());
    }

    @Test
    void approvalsInbox_listsOnlyRequestsWaitingForMe() {
        new ApprovalFlowService(steps, mock(AuditTrail.class)).start("leave", "l1", "nv001", "leave_annual");
        when(leaves.findById("l1")).thenReturn(Optional.of(leave("l1", "leave_annual", "pending", null)));

        assertEquals(1, service.list(LAN, "approvals", null).size());
        assertEquals(0, service.list(HUNG, "approvals", null).size());
        assertEquals(1, service.pendingCount(LAN));
    }

    /* ── Báo cáo vắng mặt ─────────────────────────────────────────────── */

    @Test
    void absenceSummary_managerSeesOnlyDirectReports_employeeIsForbidden() {
        when(leaves.absenceByEmployee(any(), any(), eq("mgr_lan"))).thenReturn(List.of(Map.of("userId", "nv001",
                "fullName", "Trần Văn An", "departmentName", "Kho", "absences", 1, "assignments", 20)));
        when(leaves.approvedLeaveDaysByType(any(), any(), eq("mgr_lan"))).thenReturn(List.of(Map.of("userId", "nv001",
                "leaveTypeId", "leave_annual", "leaveTypeName", "Nghỉ phép năm", "days", new BigDecimal("2.5"))));

        LeaveService.AbsenceSummary summary = service.absenceSummary(LAN, D5, D5.plusDays(30), "employee");

        assertEquals("direct_reports", summary.scope());
        assertEquals(1, summary.rows().size());
        assertEquals(5.0, summary.rows().get(0).absenceRatePercent());
        assertEquals(0, new BigDecimal("2.5").compareTo(summary.rows().get(0).leaveDays()));
        verify(leaves, atLeastOnce()).absenceByEmployee(any(), any(), eq("mgr_lan"));
        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> service.absenceSummary(AN, D5, D5.plusDays(1), "employee")).getCode());
    }

    @Test
    void absenceSummary_byDepartment_andZeroDenominatorIsNotAFakeNumber() {
        when(leaves.absenceByEmployee(any(), any(), eq(null))).thenReturn(List.of(
                Map.of("userId", "a", "fullName", "A", "departmentName", "Kho", "absences", 2, "assignments", 10),
                Map.of("userId", "b", "fullName", "B", "departmentName", "Kho", "absences", 0, "assignments", 10),
                Map.of("userId", "c", "fullName", "C", "absences", 0, "assignments", 0)));
        when(leaves.approvedLeaveDaysByType(any(), any(), eq(null))).thenReturn(List.of());

        LeaveService.AbsenceSummary summary = service.absenceSummary(ADMIN, D5, D5.plusDays(6), "department");

        LeaveService.AbsenceSummaryRow kho = summary.rows().stream().filter(r -> r.key().equals("Kho")).findFirst().orElseThrow();
        assertEquals(2, kho.employees());
        assertEquals(10.0, kho.absenceRatePercent());
        LeaveService.AbsenceSummaryRow none = summary.rows().stream().filter(r -> r.key().startsWith("(")).findFirst().orElseThrow();
        assertNull(none.absenceRatePercent());
        assertEquals("INVALID_GROUP", assertThrows(ApiException.class,
                () -> service.absenceSummary(ADMIN, D5, D5, "city")).getCode());
    }

    @Test
    void csv_escapesFormulasAndQuotes() {
        assertEquals("\"'=SUM(A1)\",\"a\"\"b\",\"-3.5\"\r\n", LeaveService.csvLine(List.of("=SUM(A1)", "a\"b", "-3.5")));
    }
}
