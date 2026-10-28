package org.dacnpm.schedulemanager.api.openshifts;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.UserDirectory;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentValidationService;
import org.dacnpm.schedulemanager.domain.schedule.ShiftInfo;
import org.dacnpm.schedulemanager.domain.schedule.Violation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ca trống (BA-03): không bao giờ xác nhận vượt số chỗ. Đồng hồ: 01/11/2026 08:00. */
class OpenShiftServiceTest {

    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final LocalDate D5 = LocalDate.of(2026, 11, 5);

    OpenShiftRepository repository;
    AssignmentMutationService schedule;
    AssignmentValidationService validator;
    OpenShiftService service;

    @BeforeEach
    void setUp() {
        repository = mock(OpenShiftRepository.class);
        schedule = mock(AssignmentMutationService.class);
        validator = mock(AssignmentValidationService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new OpenShiftService(repository, schedule, validator, mock(UserDirectory.class),
                mock(DomainEventPublisher.class), mock(AuditTrail.class), mock(JdbcTemplate.class), clock);
        when(validator.validate(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        when(schedule.findShift("sh_1")).thenReturn(Optional.of(new ShiftInfo("sh_1", "Ca tối", D5,
                LocalTime.of(14, 30), LocalTime.of(22, 30), "published", 5, 3)));
    }

    @Test
    void create_slotsCannotExceedMissingStaff() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(ADMIN,
                new OpenShiftService.CreateOpenShift("sh_1", 3, null, LocalDateTime.of(2026, 11, 4, 18, 0), null)));
        assertEquals("TOO_MANY_SLOTS", ex.getCode());
    }

    @Test
    void create_deadlineMustBeBeforeShiftStart() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(ADMIN,
                new OpenShiftService.CreateOpenShift("sh_1", 2, null, LocalDateTime.of(2026, 11, 5, 15, 0), null)));
        assertEquals("INVALID_DEADLINE", ex.getCode());
    }

    @Test
    void create_storesDeadlineInUtc() {
        when(repository.findById(anyString(), eq("admin"))).thenReturn(Optional.of(openShift("open", 0, 0)));
        service.create(ADMIN, new OpenShiftService.CreateOpenShift("sh_1", 2, null,
                LocalDateTime.of(2026, 11, 4, 18, 0), "Cần gấp"));
        // 18:00 giờ VN = 11:00 UTC
        verify(repository).insert(anyString(), eq("sh_1"), eq(2), eq(null),
                eq(LocalDateTime.of(2026, 11, 4, 11, 0)), eq("Cần gấp"), eq("admin"));
    }

    @Test
    void approve_whenNoSlotLeft_conflictAndNoAssignment() {
        when(repository.findClaim("c1")).thenReturn(Optional.of(claim("pending")));
        when(repository.findById("os1", "admin")).thenReturn(Optional.of(openShift("open", 2, 2)));
        when(repository.reserveSlot("os1")).thenReturn(0);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.approveClaim(ADMIN, "os1", "c1", new OpenShiftService.Decision(null, null)));

        assertEquals("OPEN_SHIFT_FULL", ex.getCode());
        verify(schedule, never()).addAssignment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void approve_lastSlot_addsAssignment_andRejectsOtherPendingClaims() {
        when(repository.findClaim("c1")).thenReturn(Optional.of(claim("pending")))
                .thenReturn(Optional.of(claim("approved")));
        when(repository.findById("os1", "admin"))
                .thenReturn(Optional.of(openShift("open", 1, 0)))
                .thenReturn(Optional.of(openShift("filled", 1, 1)));
        when(repository.reserveSlot("os1")).thenReturn(1);
        when(repository.decideClaim(eq("c1"), eq(0), eq("approved"), eq("admin"), any())).thenReturn(1);
        when(schedule.addAssignment(eq("sh_1"), eq("nv001"), eq("admin"), anyString())).thenReturn("asg_new");
        when(repository.rejectPendingClaims(eq("os1"), eq("admin"), anyString())).thenReturn(List.of("nv002"));

        ClaimView result = service.approveClaim(ADMIN, "os1", "c1", new OpenShiftService.Decision(0, null));

        assertEquals("approved", result.status());
        verify(repository).setClaimAssignment("c1", "asg_new");
        verify(repository).rejectPendingClaims(eq("os1"), eq("admin"), anyString());
    }

    @Test
    void approve_revalidates_andBlocks() {
        when(repository.findClaim("c1")).thenReturn(Optional.of(claim("pending")));
        when(repository.findById("os1", "admin")).thenReturn(Optional.of(openShift("open", 2, 0)));
        when(validator.validate(eq("nv001"), eq("sh_1"), any(), any(), any()))
                .thenReturn(List.of(Violation.block("MIN_REST", "Không đủ 8 giờ nghỉ.")));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.approveClaim(ADMIN, "os1", "c1", new OpenShiftService.Decision(null, null)));

        assertEquals("VALIDATION_FAILED", ex.getCode());
        verify(repository, never()).reserveSlot(anyString());
    }

    @Test
    void approve_twice_isNoOp() {
        when(repository.findClaim("c1")).thenReturn(Optional.of(claim("approved")));
        service.approveClaim(ADMIN, "os1", "c1", new OpenShiftService.Decision(null, null));
        verify(repository, never()).reserveSlot(anyString());
        verify(repository, never()).decideClaim(any(), anyInt(), any(), any(), any());
    }

    @Test
    void claim_afterDeadline_isRejected() {
        OpenShiftView expired = new OpenShiftView("os1", "sh_1", "Ca tối", D5, LocalTime.of(14, 30),
                LocalTime.of(22, 30), 2, 0, null, LocalDateTime.of(2026, 10, 31, 0, 0), null, "open", 0,
                null, null, 0, null);
        when(repository.findById("os1", "nv001")).thenReturn(Optional.of(expired));
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(AN, "os1", null));
        assertEquals("DEADLINE_PASSED", ex.getCode());
    }

    static OpenShiftView openShift(String status, int slots, int filled) {
        return new OpenShiftView("os1", "sh_1", "Ca tối", D5, LocalTime.of(14, 30), LocalTime.of(22, 30),
                slots, filled, null, LocalDateTime.of(2026, 11, 4, 11, 0), null, status, 1, null, null,
                filled, LocalDateTime.of(2026, 11, 1, 1, 0));
    }

    static ClaimView claim(String status) {
        return new ClaimView("c1", "os1", "nv001", "Trần Văn An", status, null, null, null, null, null, 0,
                LocalDateTime.of(2026, 11, 1, 1, 0));
    }
}
