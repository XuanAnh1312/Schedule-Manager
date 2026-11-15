package org.dacnpm.schedulemanager.api.requests;

import org.dacnpm.schedulemanager.api.approvals.ApprovalFlow;
import org.dacnpm.schedulemanager.api.approvals.ApprovalFlowService;
import org.dacnpm.schedulemanager.api.approvals.FakeApprovalRepository;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.UserDirectory;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.dacnpm.schedulemanager.api.requests.ShiftRequestServiceTest.assignment;
import static org.dacnpm.schedulemanager.api.requests.ShiftRequestServiceTest.view;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BAO-04: yêu cầu đổi/thay ca duyệt theo bước; lịch chỉ đổi ở bước cuối. */
class ShiftRequestApprovalStepsTest {

    static final CurrentUser BICH = new CurrentUser("nv002", false);
    static final CurrentUser LAN = new CurrentUser("mgr_lan", false);
    static final CurrentUser HUNG = new CurrentUser("mgr_hung", false);
    static final CurrentUser ADMIN = new CurrentUser("admin", true);

    ShiftRequestRepository repository;
    AssignmentMutationService schedule;
    DomainEventPublisher events;
    FakeApprovalRepository steps;
    ShiftRequestService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShiftRequestRepository.class);
        schedule = mock(AssignmentMutationService.class);
        events = mock(DomainEventPublisher.class);
        AssignmentValidationService validator = mock(AssignmentValidationService.class);
        steps = new FakeApprovalRepository();
        steps.managers.put("nv001", "mgr_lan");
        steps.mode("shift_request", null, ApprovalFlow.MANAGER_THEN_ADMIN);
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new ShiftRequestService(repository, schedule, validator, mock(UserDirectory.class), events,
                mock(AuditTrail.class), clock, new ApprovalFlowService(steps, mock(AuditTrail.class)));
        when(validator.validate(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        when(schedule.findAssignment("asg_an")).thenReturn(Optional.of(
                assignment("asg_an", "sh_1", "nv001", "Ca sáng", LocalTime.of(7, 0), LocalTime.of(15, 0))));
        when(repository.scheduleSnapshotState(anyString())).thenReturn(ShiftRequestRepository.SnapshotState.UNCHANGED);
    }

    @Test
    void peerAcceptance_startsTheFlow_andNotifiesTheRequestersManager() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_peer", "cover", 0)),
                Optional.of(view("pending_approval", "cover", 1)));
        when(repository.peerRespond(eq("req1"), anyInt(), anyString(), any())).thenReturn(1);

        service.peerRespond(BICH, "req1", true, 0, null);

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals(List.of("mgr_lan", "nv001"), event.getValue().recipients());
        assertEquals(2, steps.findSteps("shift_request", "req1").size());
    }

    @Test
    void managerStep_keepsTheSchedule_adminStepApplies() {
        new ApprovalFlowService(steps, mock(AuditTrail.class)).start("shift_request", "req1", "nv001", null);
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "cover", 1)));
        when(repository.decide(eq("req1"), anyInt(), anyList(), eq("approved"), any(), any(), any())).thenReturn(1);

        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> service.approve(HUNG, "req1", 1, null)).getCode());
        assertEquals("pending_approval", service.approve(LAN, "req1", 1, null).status());
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString());

        service.approve(ADMIN, "req1", 1, null);
        verify(schedule).reassign(eq("asg_an"), eq("nv001"), eq("nv002"), eq("admin"), anyString());
    }

    @Test
    void managerCanSeeAndReject_atTheirStep() {
        new ApprovalFlowService(steps, mock(AuditTrail.class)).start("shift_request", "req1", "nv001", null);
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "cover", 1)),
                Optional.of(view("pending_approval", "cover", 1)), Optional.of(view("rejected", "cover", 2)));
        when(repository.decide(eq("req1"), anyInt(), anyList(), eq("rejected"), any(), any(), any())).thenReturn(1);

        assertEquals("req1", service.get(LAN, "req1").id());
        assertEquals("NOT_FOUND", assertThrows(ApiException.class, () -> service.get(HUNG, "req1")).getCode());
        assertEquals("rejected", service.reject(LAN, "req1", 1, "Không hợp lý").status());
    }
}
