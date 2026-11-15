package org.dacnpm.schedulemanager.api.openshifts;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.UserDirectory;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentValidationService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BAO-02: ca trống tự chọn người. Thứ tự ưu tiên Level → giờ/mục tiêu → đăng ký sớm; kiểm tra lại ràng buộc lúc chọn;
 * không vượt số chỗ; không chạy hai lần. Đồng hồ: 01/11/2026 08:00 giờ VN = 01:00 UTC.
 */
class OpenShiftAutoSelectTest {

    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final LocalDateTime PAST_DEADLINE_UTC = LocalDateTime.of(2026, 11, 1, 0, 0);

    OpenShiftRepository repository;
    AssignmentMutationService schedule;
    AssignmentValidationService validator;
    UserDirectory users;
    JdbcTemplate jdbc;
    AuditTrail audit;
    OpenShiftService service;
    int filled;
    int capacity = 2;

    @BeforeEach
    void setUp() {
        repository = mock(OpenShiftRepository.class);
        schedule = mock(AssignmentMutationService.class);
        validator = mock(AssignmentValidationService.class);
        users = mock(UserDirectory.class);
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditTrail.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new OpenShiftService(repository, schedule, validator, users, mock(DomainEventPublisher.class), audit,
                jdbc, clock);
        when(validator.validate(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        when(jdbc.queryForObject(eq("SELECT created_by FROM open_shifts WHERE id = ?"), eq(String.class), eq("os1")))
                .thenReturn("admin");
        when(schedule.addAssignment(anyString(), anyString(), anyString(), anyString())).thenReturn("asg_new");
        when(repository.decideClaim(anyString(), anyInt(), anyString(), any(), any())).thenReturn(1);
        when(repository.findClaim(anyString())).thenAnswer(inv -> Optional.of(claim(inv.getArgument(0), "x", "approved", 0)));
        filled = 0;
        // Giữ chỗ nguyên tử: chỉ thành công khi còn chỗ (mô phỏng UPDATE ... WHERE filled_count < slots).
        when(repository.reserveSlot("os1")).thenAnswer(inv -> {
            if (filled >= capacity) return 0;
            filled++;
            return 1;
        });
    }

    private void autoOpenShift(int slots, String requiredLevel) {
        capacity = slots;
        when(repository.findById(eq("os1"), any())).thenReturn(Optional.of(view("open", slots, 0, requiredLevel, null)));
    }

    private void employee(String id, String level, double ratio) {
        when(users.find(id)).thenReturn(Optional.of(new UserDirectory.Employee(id, id, level, "user", "active")));
        when(schedule.workload(id, "sh_1")).thenReturn(new AssignmentMutationService.Workload(ratio * 40, 40.0));
    }

    @Test
    void ranksByLevelThenWorkloadThenClaimTime_andFillsOnlyTheAvailableSlots() {
        autoOpenShift(2, "lvl1");
        employee("early_heavy", "lvl1", 0.9);
        employee("light", "lvl1", 0.2);
        employee("higher_level", "lvl2", 0.0);
        employee("late_heavy", "lvl1", 0.9);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("lvl2"), eq("lvl1"))).thenReturn(1);
        when(repository.findClaims("os1")).thenReturn(List.of(
                claim("c_late", "late_heavy", "pending", 30),
                claim("c_high", "higher_level", "pending", 5),
                claim("c_light", "light", "pending", 20),
                claim("c_early", "early_heavy", "pending", 10)));

        OpenShiftService.AutoSelection result = service.autoSelect("os1");

        // Level đúng trước; trong đó tỷ lệ giờ thấp trước; bằng nhau thì ai đăng ký sớm hơn.
        assertEquals(List.of("light", "early_heavy"), result.selectedUserIds());
        assertEquals(List.of("late_heavy", "higher_level"), result.rejectedUserIds());
        verify(schedule, times(2)).addAssignment(eq("sh_1"), anyString(), eq("admin"), anyString());
        verify(repository).setAutoResult(eq("c_light"), eq(1), anyString());
        verify(repository).setAutoResult(eq("c_high"), eq(4), anyString());
        verify(repository).markAutoProcessed("os1");
    }

    @Test
    void candidateFailingValidationAtSelectionTime_isSkipped_andTheNextOneIsChosen() {
        autoOpenShift(1, null);
        employee("a", "lvl1", 0.1);
        employee("b", "lvl1", 0.5);
        when(validator.validate(eq("a"), eq("sh_1"), any(), any(), any()))
                .thenReturn(List.of(Violation.exceptionable("NOT_AVAILABLE_TIME", "Chưa đăng ký rảnh")));
        when(repository.findClaims("os1")).thenReturn(List.of(claim("ca", "a", "pending", 1), claim("cb", "b", "pending", 2)));

        OpenShiftService.AutoSelection result = service.autoSelect("os1");

        // Tự động không duyệt ngoại lệ: vi phạm nhóm ngoại lệ cũng bị loại.
        assertEquals(List.of("b"), result.selectedUserIds());
        verify(repository).decideClaim(eq("ca"), anyInt(), eq("rejected"), eq("admin"), anyString());
    }

    @Test
    void notEnoughCandidates_closesTheOpenShift_andWarnsAdmins() {
        autoOpenShift(2, null);
        employee("a", "lvl1", 0.1);
        when(repository.findClaims("os1")).thenReturn(List.of(claim("ca", "a", "pending", 1)));

        OpenShiftService.AutoSelection result = service.autoSelect("os1");

        assertEquals(List.of("a"), result.selectedUserIds());
        verify(repository).closeIfOpen("os1");
    }

    @Test
    void alreadyProcessed_doesNothing() {
        when(repository.findById(eq("os1"), any())).thenReturn(Optional.of(
                view("open", 2, 0, null, LocalDateTime.of(2026, 11, 1, 0, 30))));

        OpenShiftService.AutoSelection result = service.autoSelect("os1");

        assertFalse(result.processed());
        verify(repository, never()).reserveSlot(anyString());
        verify(repository, never()).markAutoProcessed(anyString());
    }

    @Test
    void locksTheRowBeforeReadingIt() {
        autoOpenShift(1, null);
        when(repository.findClaims("os1")).thenReturn(List.of());

        service.autoSelect("os1");

        var order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).lock("os1");
        order.verify(repository).findById(eq("os1"), any());
    }

    @Test
    void beforeDeadline_orManualMode_isRejected() {
        when(repository.findById(eq("os1"), any())).thenReturn(Optional.of(new OpenShiftView("os1", "sh_1", "Ca tối",
                LocalDate.of(2026, 11, 5), LocalTime.of(14, 30), LocalTime.of(22, 30), 1, 0, null,
                LocalDateTime.of(2026, 11, 3, 0, 0), null, "open", 0, null, null, 1, null, "auto", null)));
        assertEquals("DEADLINE_NOT_REACHED", assertThrows(ApiException.class, () -> service.autoSelect("os1")).getCode());

        when(repository.findById(eq("os1"), any())).thenReturn(Optional.of(new OpenShiftView("os1", "sh_1", "Ca tối",
                LocalDate.of(2026, 11, 5), LocalTime.of(14, 30), LocalTime.of(22, 30), 1, 0, null,
                PAST_DEADLINE_UTC, null, "open", 0, null, null, 1, null, "manual", null)));
        assertEquals("NOT_AUTO", assertThrows(ApiException.class, () -> service.autoSelect("os1")).getCode());
    }

    @Test
    void runBySomeoneOtherThanAdmin_isForbidden() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.runAutoSelection(new CurrentUser("nv001", false), "os1"));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    void create_rejectsUnknownSelectionMode() {
        when(schedule.findShift("sh_1")).thenReturn(Optional.of(new org.dacnpm.schedulemanager.domain.schedule.ShiftInfo(
                "sh_1", "Ca tối", LocalDate.of(2026, 11, 5), LocalTime.of(14, 30), LocalTime.of(22, 30), "published", 5, 3)));
        ApiException ex = assertThrows(ApiException.class, () -> service.create(ADMIN, new OpenShiftService.CreateOpenShift(
                "sh_1", 1, null, LocalDateTime.of(2026, 11, 4, 18, 0), null, "random")));
        assertEquals("INVALID_SELECTION_MODE", ex.getCode());
    }

    static OpenShiftView view(String status, int slots, int filled, String level, LocalDateTime processedAt) {
        return new OpenShiftView("os1", "sh_1", "Ca tối", LocalDate.of(2026, 11, 5), LocalTime.of(14, 30),
                LocalTime.of(22, 30), slots, filled, level, PAST_DEADLINE_UTC, null, status, 0, null, null, 1,
                LocalDateTime.of(2026, 10, 30, 1, 0), "auto", processedAt);
    }

    static ClaimView claim(String id, String userId, String status, int minute) {
        return new ClaimView(id, "os1", userId, userId, status, null, null, null, null, null, 0,
                LocalDateTime.of(2026, 10, 31, 1, minute), null, null);
    }
}
