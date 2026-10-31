package org.dacnpm.schedulemanager.api.requests;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.UserDirectory;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.events.EventTypes;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentInfo;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentValidationService;
import org.dacnpm.schedulemanager.domain.schedule.Violation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kiểm thử nghiệp vụ đổi ca / làm thay (BA-01, BA-02, AC-08) không cần DB.
 * Đồng hồ cố định: 01/11/2026 08:00 (Asia/Ho_Chi_Minh); các ca ở ngày 05/11 → tương lai.
 */
class ShiftRequestServiceTest {

    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final CurrentUser BICH = new CurrentUser("nv002", false);
    static final CurrentUser ADMIN = new CurrentUser("admin", true);

    ShiftRequestRepository repository;
    AssignmentMutationService schedule;
    AssignmentValidationService validator;
    UserDirectory users;
    DomainEventPublisher events;
    AuditTrail audit;
    ShiftRequestService service;

    final AssignmentInfo anMorning = assignment("asg_an", "sh_1", "nv001", "Ca sáng", LocalTime.of(7, 0), LocalTime.of(15, 0));
    final AssignmentInfo bichEvening = assignment("asg_bich", "sh_2", "nv002", "Ca tối", LocalTime.of(14, 30), LocalTime.of(22, 30));

    @BeforeEach
    void setUp() {
        repository = mock(ShiftRequestRepository.class);
        schedule = mock(AssignmentMutationService.class);
        validator = mock(AssignmentValidationService.class);
        users = mock(UserDirectory.class);
        events = mock(DomainEventPublisher.class);
        audit = mock(AuditTrail.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new ShiftRequestService(repository, schedule, validator, users, events, audit, clock);

        when(users.find("nv002")).thenReturn(Optional.of(
                new UserDirectory.Employee("nv002", "Lê Thị Bích", "lvl1", "user", "active")));
        when(schedule.findAssignment("asg_an")).thenReturn(Optional.of(anMorning));
        when(schedule.findAssignment("asg_bich")).thenReturn(Optional.of(bichEvening));
        when(validator.validate(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
    }

    /* ── Gửi yêu cầu ─────────────────────────────────────────────────── */

    @Test
    void create_cover_savesPendingPeer_notifiesTarget_andDoesNotTouchSchedule() {
        when(repository.findById(anyString())).thenReturn(Optional.of(view("pending_peer", "cover", 0)));

        ShiftRequestView result = service.create(AN,
                new ShiftRequestService.CreateRequest("cover", "asg_an", "nv002", null, "Có việc gia đình"));

        assertEquals("pending_peer", result.status());
        verify(repository).insert(anyString(), eq("cover"), eq("nv001"), eq("asg_an"), eq("sh_1"),
                eq("nv002"), eq(null), eq(null), eq("Có việc gia đình"));
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals(EventTypes.SHIFT_REQUEST_CREATED, event.getValue().type());
        assertEquals(List.of("nv002"), event.getValue().recipients());
        // AC-08: chưa duyệt thì lịch chính thức không đổi
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void create_rejectsWhenTargetViolatesConstraints() {
        when(validator.validate(eq("nv002"), eq("sh_1"), any(), any(), any()))
                .thenReturn(List.of(Violation.block("OVERLAP", "Trùng lịch với Ca khác.")));

        ApiException ex = assertThrows(ApiException.class, () -> service.create(AN,
                new ShiftRequestService.CreateRequest("cover", "asg_an", "nv002", null, "Bận")));

        assertEquals("VALIDATION_FAILED", ex.getCode());
        assertTrue(ex.getDetails().get(0).contains("Trùng lịch"));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_rejectsShiftNotOwnedByRequester() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(BICH,
                new ShiftRequestService.CreateRequest("cover", "asg_an", "nv002", null, "x")));
        // Bích không thể tự gửi yêu cầu cho chính mình / ca của người khác
        assertEquals("INVALID_TARGET", ex.getCode());

        when(users.find("nv003")).thenReturn(Optional.of(
                new UserDirectory.Employee("nv003", "Châu", "lvl1", "user", "active")));
        ApiException ex2 = assertThrows(ApiException.class, () -> service.create(BICH,
                new ShiftRequestService.CreateRequest("cover", "asg_an", "nv003", null, "x")));
        assertEquals("SCHEDULE_CHANGED", ex2.getCode());
    }

    @Test
    void create_rejectsPastShift() {
        AssignmentInfo past = new AssignmentInfo("asg_old", "sh_0", "nv001", "confirmed", "Ca sáng",
                LocalDate.of(2026, 10, 20), LocalTime.of(7, 0), LocalTime.of(15, 0), "published");
        when(schedule.findAssignment("asg_old")).thenReturn(Optional.of(past));

        ApiException ex = assertThrows(ApiException.class, () -> service.create(AN,
                new ShiftRequestService.CreateRequest("cover", "asg_old", "nv002", null, "x")));
        assertEquals("SHIFT_STARTED", ex.getCode());
    }

    @Test
    void create_adminIsForbidden() {
        assertThrows(ApiException.class, () -> service.create(ADMIN,
                new ShiftRequestService.CreateRequest("cover", "asg_an", "nv002", null, "x")));
    }

    /* ── Người nhận phản hồi ─────────────────────────────────────────── */

    @Test
    void peerRespond_onlyTargetMayAnswer() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_peer", "cover", 0)));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.peerRespond(AN, "req1", true, 0, null));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    void peerAccept_movesToPendingApproval_andNotifiesAdmins() {
        when(repository.findById("req1"))
                .thenReturn(Optional.of(view("pending_peer", "cover", 0)))
                .thenReturn(Optional.of(view("pending_approval", "cover", 1)));
        when(repository.peerRespond("req1", 0, "pending_approval", null)).thenReturn(1);

        ShiftRequestView result = service.peerRespond(BICH, "req1", true, 0, null);

        assertEquals("pending_approval", result.status());
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertTrue(event.getValue().recipients().contains(DomainEvent.ROLE_ADMIN));
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /* ── Duyệt ───────────────────────────────────────────────────────── */

    @Test
    void approve_cover_reassignsOnce_recordsFinalResponsible_andNotifiesBoth() {
        when(repository.findById("req1"))
                .thenReturn(Optional.of(view("pending_approval", "cover", 1)))
                .thenReturn(Optional.of(view("approved", "cover", 2)));
        when(repository.decide(eq("req1"), eq(1), anyList(), eq("approved"), eq("admin"), any(), eq("nv002")))
                .thenReturn(1);

        ShiftRequestView result = service.approve(ADMIN, "req1", 1, "OK");

        assertEquals("approved", result.status());
        verify(schedule).reassign(eq("asg_an"), eq("nv001"), eq("nv002"), eq("admin"), anyString());
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals(EventTypes.SHIFT_REQUEST_APPROVED, event.getValue().type());
        assertTrue(event.getValue().recipients().containsAll(List.of("nv001", "nv002")));
    }

    @Test
    void approve_swap_removesBothOldAssignmentsBeforeAddingReplacements() {
        when(repository.findById("req1"))
                .thenReturn(Optional.of(view("pending_approval", "swap", 1)))
                .thenReturn(Optional.of(view("approved", "swap", 2)));
        when(repository.decide(eq("req1"), eq(1), anyList(), eq("approved"), any(), any(), any())).thenReturn(1);

        service.approve(ADMIN, "req1", null, null);

        var order = org.mockito.Mockito.inOrder(schedule);
        order.verify(schedule).cancelAssignment(eq("asg_an"), eq("nv001"), eq("admin"), anyString());
        order.verify(schedule).cancelAssignment(eq("asg_bich"), eq("nv002"), eq("admin"), anyString());
        order.verify(schedule).addAssignment(eq("sh_1"), eq("nv002"), eq("admin"), anyString());
        order.verify(schedule).addAssignment(eq("sh_2"), eq("nv001"), eq("admin"), anyString());
    }

    @Test
    void approve_twice_doesNotChangeScheduleAgain() {
        ShiftRequestView approved = view("approved", "cover", 2);
        when(repository.findById("req1")).thenReturn(Optional.of(approved));

        ShiftRequestView result = service.approve(ADMIN, "req1", 1, null);

        assertSame(approved, result);
        verify(repository, never()).decide(any(), anyInt(), anyList(), any(), any(), any(), any());
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(events, never()).publish(any());
    }

    @Test
    void approve_concurrentDuplicate_secondCallDoesNotReassign() {
        when(repository.findById("req1"))
                .thenReturn(Optional.of(view("pending_approval", "cover", 1)))
                .thenReturn(Optional.of(view("approved", "cover", 2)));
        when(repository.decide(any(), anyInt(), anyList(), any(), any(), any(), any())).thenReturn(0);

        ShiftRequestView result = service.approve(ADMIN, "req1", 1, null);

        assertEquals("approved", result.status());
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void approve_revalidatesAtDecisionTime_andBlocksOnViolation() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "cover", 1)));
        when(validator.validate(eq("nv002"), eq("sh_1"), any(), any(), any()))
                .thenReturn(List.of(Violation.block("MAX_HOURS_WEEK", "Vượt 48 giờ/tuần.")));

        ApiException ex = assertThrows(ApiException.class, () -> service.approve(ADMIN, "req1", 1, null));

        assertEquals("VALIDATION_FAILED", ex.getCode());
        verify(repository, never()).decide(any(), anyInt(), anyList(), any(), any(), any(), any());
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void approve_failsWhenScheduleChangedSinceRequest() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "cover", 1)));
        // Admin đã chuyển ca của An cho người khác sau khi An gửi yêu cầu
        when(schedule.findAssignment("asg_an")).thenReturn(Optional.of(
                assignment("asg_an", "sh_1", "nv005", "Ca sáng", LocalTime.of(7, 0), LocalTime.of(15, 0))));

        ApiException ex = assertThrows(ApiException.class, () -> service.approve(ADMIN, "req1", 1, null));

        assertEquals("SCHEDULE_CHANGED", ex.getCode());
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void approve_staleVersionIsRejected() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "cover", 3)));
        ApiException ex = assertThrows(ApiException.class, () -> service.approve(ADMIN, "req1", 1, null));
        assertEquals("STALE_VERSION", ex.getCode());
    }

    @Test
    void approve_requiresAdmin() {
        ApiException ex = assertThrows(ApiException.class, () -> service.approve(AN, "req1", 1, null));
        assertEquals("FORBIDDEN", ex.getCode());
        verify(repository, never()).findById(any());
    }

    @Test
    void reject_requiresReason() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "cover", 1)));
        ApiException ex = assertThrows(ApiException.class, () -> service.reject(ADMIN, "req1", 1, " "));
        assertEquals("REQUIRED", ex.getCode());
    }

    @Test
    void cancel_onlyRequester() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_peer", "cover", 0)));
        assertThrows(ApiException.class, () -> service.cancel(BICH, "req1", 0));
        verify(repository, times(0)).cancel(any(), anyInt());
    }

    /* ── Fixture ─────────────────────────────────────────────────────── */

    static AssignmentInfo assignment(String id, String shiftId, String userId, String name, LocalTime start, LocalTime end) {
        return new AssignmentInfo(id, shiftId, userId, "confirmed", name, LocalDate.of(2026, 11, 5), start, end,
                "published");
    }

    static ShiftRequestView view(String status, String type, int version) {
        boolean swap = "swap".equals(type);
        ShiftSummary source = new ShiftSummary("sh_1", "Ca sáng", LocalDate.of(2026, 11, 5),
                LocalTime.of(7, 0), LocalTime.of(15, 0));
        ShiftSummary target = swap ? new ShiftSummary("sh_2", "Ca tối", LocalDate.of(2026, 11, 5),
                LocalTime.of(14, 30), LocalTime.of(22, 30)) : null;
        return new ShiftRequestView("req1", type, status, "nv001", "Trần Văn An", "asg_an", source,
                "nv002", "Lê Thị Bích", swap ? "asg_bich" : null, target, "Có việc", null, null,
                null, null, null, null, "approved".equals(status) ? "nv002" : null, version,
                LocalDateTime.of(2026, 11, 1, 1, 0), LocalDateTime.of(2026, 11, 1, 1, 0));
    }
}
