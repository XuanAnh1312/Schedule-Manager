package org.dacnpm.schedulemanager.api.leave;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.events.EventTypes;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentInfo;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.ShiftInfo;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nghỉ phép cơ bản (BA-04). Đồng hồ cố định: 01/11/2026. */
class LeaveServiceTest {

    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final LocalDate D5 = LocalDate.of(2026, 11, 5);

    LeaveRepository repository;
    AssignmentMutationService schedule;
    DomainEventPublisher events;
    LeaveService service;

    @BeforeEach
    void setUp() {
        repository = mock(LeaveRepository.class);
        schedule = mock(AssignmentMutationService.class);
        events = mock(DomainEventPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new LeaveService(repository, schedule, events, mock(AuditTrail.class), clock);

        when(repository.findType("leave_annual")).thenReturn(Optional.of(
                new LeaveType("leave_annual", "Nghỉ phép năm", null, true, false, 0, true)));
        when(repository.findType("leave_sick")).thenReturn(Optional.of(
                new LeaveType("leave_sick", "Nghỉ ốm", null, true, true, 0, true)));
        when(repository.findType("leave_notice")).thenReturn(Optional.of(
                new LeaveType("leave_notice", "Nghỉ dài", null, false, false, 7, true)));
    }

    @Test
    void create_pending_notifiesAdminsWithImpact() {
        when(repository.findById(anyString())).thenReturn(Optional.of(view("pending", 0)));
        stubOneAffectedShift(5, 5);

        LeaveView v = service.create(AN, new LeaveService.CreateLeave("leave_annual", D5, D5, "Về quê", null));

        assertEquals("pending", v.status());
        ArgumentCaptor<DomainEvent> e = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(e.capture());
        assertEquals(EventTypes.LEAVE_REQUESTED, e.getValue().type());
        assertEquals(List.of(DomainEvent.ROLE_ADMIN), e.getValue().recipients());
        assertTrue(e.getValue().body().contains("1 ca"));
        // Gửi đơn không đụng vào lịch
        verify(schedule, never()).cancelAssignment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void create_documentRequiredForSickLeave() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(AN,
                new LeaveService.CreateLeave("leave_sick", D5, D5, "Ốm", null)));
        assertEquals("DOCUMENT_REQUIRED", ex.getCode());
    }

    @Test
    void create_respectsMinimumNotice() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(AN,
                new LeaveService.CreateLeave("leave_notice", D5, D5, "Đi xa", null)));
        assertEquals("NOTICE_TOO_SHORT", ex.getCode());
    }

    @Test
    void create_rejectsPastDatesAndBadRange() {
        assertEquals("NOTICE_TOO_SHORT", assertThrows(ApiException.class, () -> service.create(AN,
                new LeaveService.CreateLeave("leave_annual", LocalDate.of(2026, 10, 30), D5, "x", null))).getCode());
        assertEquals("INVALID_RANGE", assertThrows(ApiException.class, () -> service.create(AN,
                new LeaveService.CreateLeave("leave_annual", D5, D5.minusDays(1), "x", null))).getCode());
    }

    @Test
    void create_rejectsOverlapWithExistingLeave() {
        when(repository.overlapsActive("nv001", D5, D5)).thenReturn(true);
        ApiException ex = assertThrows(ApiException.class, () -> service.create(AN,
                new LeaveService.CreateLeave("leave_annual", D5, D5, "x", null)));
        assertEquals("LEAVE_OVERLAP", ex.getCode());
    }

    @Test
    void approve_cancelsAffectedAssignments_andWarnsAboutCoverage() {
        when(repository.findById("leave1"))
                .thenReturn(Optional.of(view("pending", 0)))
                .thenReturn(Optional.of(view("approved", 1)));
        when(repository.decide("leave1", 0, "approved", "admin", null)).thenReturn(1);
        stubOneAffectedShift(5, 5); // ca đang đủ 5/5 → sau duyệt còn 4/5

        LeaveView v = service.approve(ADMIN, "leave1", 0, null);

        assertEquals("approved", v.status());
        verify(schedule).cancelAssignment(eq("asg_an"), eq("nv001"), eq("admin"), anyString());
        verify(repository).setAffectedCount("leave1", 1);
        ArgumentCaptor<DomainEvent> e = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, times(2)).publish(e.capture());
        assertEquals(EventTypes.LEAVE_APPROVED, e.getAllValues().get(0).type());
        assertEquals(EventTypes.COVERAGE_WARNING, e.getAllValues().get(1).type());
        assertTrue(e.getAllValues().get(1).body().contains("4/5"));
    }

    @Test
    void approve_twice_doesNotCancelAgain() {
        when(repository.findById("leave1")).thenReturn(Optional.of(view("approved", 1)));
        service.approve(ADMIN, "leave1", 0, null);
        verify(repository, never()).decide(any(), anyInt(), any(), any(), any());
        verify(schedule, never()).cancelAssignment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void approve_requiresAdmin() {
        assertThrows(ApiException.class, () -> service.approve(AN, "leave1", 0, null));
    }

    @Test
    void reject_requiresReason_and_cancelOnlyOwnPending() {
        when(repository.findById("leave1")).thenReturn(Optional.of(view("pending", 0)));
        assertEquals("REQUIRED", assertThrows(ApiException.class,
                () -> service.reject(ADMIN, "leave1", 0, "")).getCode());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> service.cancel(new CurrentUser("nv002", false), "leave1", 0)).getCode());
    }

    @Test
    void recordAbsence_rejectsFutureShift() {
        when(schedule.findAssignment("asg_an")).thenReturn(Optional.of(assignmentOn(D5)));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordAbsence(ADMIN, new LeaveService.AbsenceInput("asg_an", "Không đến")));
        assertEquals("FUTURE_SHIFT", ex.getCode());
    }

    @Test
    void recordAbsence_pastShift_isRecorded() {
        when(schedule.findAssignment("asg_an")).thenReturn(Optional.of(assignmentOn(LocalDate.of(2026, 10, 31))));
        var record = service.recordAbsence(ADMIN, new LeaveService.AbsenceInput("asg_an", "Không đến"));
        assertEquals("nv001", record.get("userId"));
        verify(repository).insertAbsence(anyString(), eq("nv001"), eq("sh_1"), eq("asg_an"), eq("Không đến"), eq("admin"));
    }

    /* ── Fixture ─────────────────────────────────────────────────────── */

    private void stubOneAffectedShift(int required, int confirmed) {
        when(schedule.findConfirmedAssignments("nv001", D5, D5)).thenReturn(List.of(assignmentOn(D5)));
        when(schedule.findShift("sh_1")).thenReturn(Optional.of(new ShiftInfo("sh_1", "Ca tối", D5,
                LocalTime.of(14, 30), LocalTime.of(22, 30), "published", required, confirmed)));
    }

    static AssignmentInfo assignmentOn(LocalDate date) {
        return new AssignmentInfo("asg_an", "sh_1", "nv001", "confirmed", "Ca tối", date,
                LocalTime.of(14, 30), LocalTime.of(22, 30), "published");
    }

    static LeaveView view(String status, int version) {
        return new LeaveView("leave1", "nv001", "Trần Văn An", "leave_annual", "Nghỉ phép năm", D5, D5,
                "Về quê", null, status, null, null, null, null, 0, version, LocalDateTime.of(2026, 11, 1, 1, 0));
    }
}
