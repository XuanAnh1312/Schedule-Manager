package org.dacnpm.schedulemanager.api.requests;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.UserDirectory;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService.ExceptionOverride;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService.ExceptionPreview;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentValidationService;
import org.dacnpm.schedulemanager.domain.schedule.Violation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.dacnpm.schedulemanager.api.requests.ShiftRequestServiceTest.assignment;
import static org.dacnpm.schedulemanager.api.requests.ShiftRequestServiceTest.view;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BAO-02: duyệt đổi/thay ca có vi phạm thuộc nhóm ngoại lệ. Ràng buộc cứng không bao giờ được duyệt; vi phạm nhóm
 * ngoại lệ chỉ duyệt được khi có xác nhận + lý do + token xem trước, và lõi lịch nhận đúng override từng vị trí.
 */
class ShiftRequestExceptionApprovalTest {

    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final Violation NOT_AVAILABLE = Violation.exceptionable("NOT_AVAILABLE_TIME", "Chưa đăng ký rảnh giờ này.");
    static final Violation OVERLAP = Violation.block("OVERLAP", "Trùng giờ với ca khác.");

    ShiftRequestRepository repository;
    AssignmentMutationService schedule;
    AssignmentValidationService validator;
    DomainEventPublisher events;
    AuditTrail audit;
    ShiftRequestService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShiftRequestRepository.class);
        schedule = mock(AssignmentMutationService.class);
        validator = mock(AssignmentValidationService.class);
        events = mock(DomainEventPublisher.class);
        audit = mock(AuditTrail.class);
        UserDirectory users = mock(UserDirectory.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new ShiftRequestService(repository, schedule, validator, users, events, audit, clock,
                new org.dacnpm.schedulemanager.api.approvals.ApprovalFlowService(mock(org.dacnpm.schedulemanager.api.approvals.ApprovalRepository.class), mock(AuditTrail.class)));
        when(users.find("nv002")).thenReturn(Optional.of(
                new UserDirectory.Employee("nv002", "Lê Thị Bích", "lvl1", "user", "active")));
        when(schedule.findAssignment("asg_an")).thenReturn(Optional.of(
                assignment("asg_an", "sh_1", "nv001", "Ca sáng", LocalTime.of(7, 0), LocalTime.of(15, 0))));
        when(schedule.findAssignment("asg_bich")).thenReturn(Optional.of(
                assignment("asg_bich", "sh_2", "nv002", "Ca tối", LocalTime.of(14, 30), LocalTime.of(22, 30))));
        when(validator.validate(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        when(repository.captureScheduleSnapshot(anyString())).thenReturn(1);
        when(repository.scheduleSnapshotState(anyString())).thenReturn(ShiftRequestRepository.SnapshotState.UNCHANGED);
    }

    private void pendingCover() {
        when(repository.findById("req1"))
                .thenReturn(Optional.of(view("pending_approval", "cover", 1)))
                .thenReturn(Optional.of(view("approved", "cover", 2)));
        when(repository.decide(eq("req1"), eq(1), anyList(), eq("approved"), any(), any(), any())).thenReturn(1);
    }

    @Test
    void create_onlyExceptionableViolation_isAccepted_soTheApproverCanDecide() {
        when(validator.validate(eq("nv002"), eq("sh_1"), any(), any(), any())).thenReturn(List.of(NOT_AVAILABLE));
        when(repository.findById(anyString())).thenReturn(Optional.of(view("pending_peer", "cover", 0)));

        service.create(AN, new ShiftRequestService.CreateRequest("cover", "asg_an", "nv002", null, "Bận"));

        verify(repository).insert(anyString(), eq("cover"), eq("nv001"), eq("asg_an"), eq("sh_1"), eq("nv002"),
                any(), any(), eq("Bận"));
    }

    @Test
    void create_hardViolation_isStillRejected() {
        when(validator.validate(eq("nv002"), eq("sh_1"), any(), any(), any())).thenReturn(List.of(OVERLAP, NOT_AVAILABLE));

        ApiException ex = assertThrows(ApiException.class, () -> service.create(AN,
                new ShiftRequestService.CreateRequest("cover", "asg_an", "nv002", null, "Bận")));

        assertEquals("VALIDATION_FAILED", ex.getCode());
        assertEquals(List.of("Người nhận: Trùng giờ với ca khác."), ex.getDetails());
    }

    @Test
    void approve_exceptionableWithoutException_requiresException_andChangesNothing() {
        pendingCover();
        when(validator.validate(eq("nv002"), eq("sh_1"), any(), any(), any())).thenReturn(List.of(NOT_AVAILABLE));

        ApiException ex = assertThrows(ApiException.class, () -> service.approve(ADMIN, "req1", 1, null));

        assertEquals("EXCEPTION_REQUIRED", ex.getCode());
        verify(repository, never()).decide(anyString(), anyInt(), anyList(), anyString(), any(), any(), any());
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void approve_withoutReason_orWithoutConfirm_isRejected() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "cover", 1)));
        when(validator.validate(eq("nv002"), eq("sh_1"), any(), any(), any())).thenReturn(List.of(NOT_AVAILABLE));

        ApiException noConfirm = assertThrows(ApiException.class, () -> service.approve(ADMIN, "req1", 1, null,
                new ShiftRequestService.ExceptionApproval("Thiếu người", false, Map.of("source", "tok"))));
        assertEquals("CONFIRM_REQUIRED", noConfirm.getCode());

        ApiException noReason = assertThrows(ApiException.class, () -> service.approve(ADMIN, "req1", 1, null,
                new ShiftRequestService.ExceptionApproval("  ", true, Map.of("source", "tok"))));
        assertEquals("REQUIRED", noReason.getCode());
        verify(repository, never()).decide(anyString(), anyInt(), anyList(), anyString(), any(), any(), any());
    }

    @Test
    void approve_hardViolation_cannotBeOverriddenEvenWithAnException() {
        pendingCover();
        when(validator.validate(eq("nv002"), eq("sh_1"), any(), any(), any())).thenReturn(List.of(OVERLAP, NOT_AVAILABLE));

        ApiException ex = assertThrows(ApiException.class, () -> service.approve(ADMIN, "req1", 1, null,
                new ShiftRequestService.ExceptionApproval("Thiếu người", true, Map.of("source", "tok"))));

        assertEquals("VALIDATION_FAILED", ex.getCode());
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void approve_cover_withValidException_passesOverrideToScheduleCore_andAuditsTheReason() {
        pendingCover();
        when(validator.validate(eq("nv002"), eq("sh_1"), any(), any(), any())).thenReturn(List.of(NOT_AVAILABLE));

        ShiftRequestView result = service.approve(ADMIN, "req1", 1, "OK",
                new ShiftRequestService.ExceptionApproval("Thiếu người phút chót", true, Map.of("source", "tok_src")));

        assertEquals("approved", result.status());
        ArgumentCaptor<ExceptionOverride> override = ArgumentCaptor.forClass(ExceptionOverride.class);
        verify(schedule).reassign(eq("asg_an"), eq("nv001"), eq("nv002"), eq("admin"), anyString(), override.capture());
        assertEquals("tok_src", override.getValue().previewToken());
        assertEquals("Thiếu người phút chót", override.getValue().reason());
        assertTrue(override.getValue().confirm());
        verify(audit).record(eq("shift_request_approve_exception"), anyString(), anyString(), eq("admin"), any(), any(),
                eq("Ngoại lệ: Thiếu người phút chót · Ghi chú: OK"));
    }

    @Test
    void approve_swap_withException_sendsOneTokenPerSide() {
        when(repository.findById("req1"))
                .thenReturn(Optional.of(view("pending_approval", "swap", 1)))
                .thenReturn(Optional.of(view("approved", "swap", 2)));
        when(repository.decide(eq("req1"), eq(1), anyList(), eq("approved"), any(), any(), any())).thenReturn(1);
        when(validator.validate(eq("nv001"), eq("sh_2"), any(), any(), any())).thenReturn(List.of(NOT_AVAILABLE));

        service.approve(ADMIN, "req1", 1, null, new ShiftRequestService.ExceptionApproval("Đồng ý ngoại lệ", true,
                Map.of("source", "tok_src", "target", "tok_tgt")));

        ArgumentCaptor<ExceptionOverride> first = ArgumentCaptor.forClass(ExceptionOverride.class);
        ArgumentCaptor<ExceptionOverride> second = ArgumentCaptor.forClass(ExceptionOverride.class);
        verify(schedule).swap(eq("asg_an"), eq("nv001"), eq("asg_bich"), eq("nv002"), eq("admin"), anyString(),
                first.capture(), second.capture());
        assertEquals("tok_src", first.getValue().previewToken());
        assertEquals("tok_tgt", second.getValue().previewToken());
    }

    @Test
    void approve_withoutViolations_ignoresTheExceptionPayload_andUsesTheNormalPath() {
        pendingCover();

        service.approve(ADMIN, "req1", 1, null,
                new ShiftRequestService.ExceptionApproval("Không cần", true, Map.of("source", "tok")));

        verify(schedule).reassign(eq("asg_an"), eq("nv001"), eq("nv002"), eq("admin"), anyString());
        verify(schedule, never()).reassign(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void preview_aggregatesBothSidesOfASwap() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "swap", 1)));
        when(schedule.previewSwap("asg_an", "nv001", "asg_bich", "nv002", "admin")).thenReturn(List.of(
                new ExceptionPreview("sh_1", "nv002", false, false, List.of(), List.of(), "t0", "Không có vi phạm"),
                new ExceptionPreview("sh_2", "nv001", true, true, List.of(), List.of("Chưa đăng ký rảnh"), "t1", "x")));

        ShiftRequestService.ApprovalPreview preview = service.previewApproval(ADMIN, "req1");

        assertTrue(preview.exceptionNeeded());
        assertTrue(preview.allowed());
        assertEquals(List.of("Trần Văn An: Chưa đăng ký rảnh"), preview.exceptionable());
        assertEquals("target", preview.sides().get(1).side());
        assertEquals("t1", preview.sides().get(1).preview().previewToken());
    }

    @Test
    void preview_withHardViolation_isNotAllowed_andEmployeesCannotPreview() {
        when(repository.findById("req1")).thenReturn(Optional.of(view("pending_approval", "cover", 1)));
        when(schedule.previewReassign("asg_an", "nv002", "admin")).thenReturn(
                new ExceptionPreview("sh_1", "nv002", true, false, List.of("Trùng giờ"), List.of("Chưa rảnh"), "t", "x"));

        ShiftRequestService.ApprovalPreview preview = service.previewApproval(ADMIN, "req1");
        assertFalse(preview.allowed());
        assertEquals(List.of("Lê Thị Bích: Trùng giờ"), preview.blocking());

        ApiException ex = assertThrows(ApiException.class, () -> service.previewApproval(AN, "req1"));
        assertEquals("FORBIDDEN", ex.getCode());
    }
}
