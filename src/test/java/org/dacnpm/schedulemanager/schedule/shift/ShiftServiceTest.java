package org.dacnpm.schedulemanager.schedule.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftService.CancelResult;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShiftServiceTest {

    private final ShiftRepository shifts = mock(ShiftRepository.class);
    private final ScheduleDataRepository data = mock(ScheduleDataRepository.class);
    private ShiftService service;

    private static ShiftRequest userShift(LocalDate date, Set<String> mandatory) {
        return new ShiftRequest("Ca test", date, t(8, 0), t(12, 0), 0, null, "user", 1, null, null, null,
                null, mandatory, null, null, null, null);
    }

    private static ShiftDetail detail(String id, ShiftRequest r, int version) {
        return new ShiftDetail(id, r, "not_scheduled", version, false, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        service = new ShiftService(shifts, data, new AssignmentValidator(), new ObjectMapper().findAndRegisterModules());
        when(shifts.missingIds(anyString(), any())).thenReturn(List.of());
        when(shifts.periodIdFor(any())).thenReturn(Optional.of("p1"));
        when(shifts.confirmedAssignees(anyString())).thenReturn(Set.of());
    }

    private void givenData(List<Employee> staff, List<ShiftInfo> existingShifts, boolean everyoneAvailable,
                           List<org.dacnpm.schedulemanager.schedule.model.Assignment> saved) {
        when(data.load("p1")).thenReturn(Optional.of(new ScheduleData(PERIOD, rules(8, 60.0), staff, existingShifts,
                List.of(), everyoneAvailable ? freeAllPeriod(staff) : Map.of(), saved)));
    }

    @Test
    void create_rejectsMandatoryUserWhoFailsAHardConstraint_andSavesNothing() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        ShiftInfo existing = shift("old", day(0), t(10, 0), t(14, 0), 0, 1, "user");
        givenData(staff, List.of(existing), true, List.of(saved("a1", "old", "u1")));

        assertThatThrownBy(() -> service.create(userShift(day(0), Set.of("u1")), "admin"))
                .isInstanceOfSatisfying(ShiftRejectedException.class, e ->
                        assertThat(e.violations()).anyMatch(v -> v.code() == ViolationCode.OVERLAP && v.userId().equals("u1")));
        verify(shifts, never()).insert(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void create_availabilityOnlyViolation_isAWarningNotABlock() {
        List<Employee> staff = List.of(employee("u1", "lvl1", 100.0));
        givenData(staff, List.of(), false, List.of());
        ShiftRequest request = userShift(day(0), Set.of("u1"));
        when(shifts.findById(anyString())).thenReturn(Optional.of(detail("shift_x", request, 1)));

        ShiftDetail created = service.create(request, "admin");

        assertThat(created.id()).isEqualTo("shift_x");
        verify(shifts).insert(anyString(), eq(request), eq(false), any());
        verify(shifts).writeHistory(eq("shift_create"), anyString(), any(), eq("admin"), any(), any(), any());
    }

    @Test
    void create_withoutNamedUsers_doesNotNeedAPeriod() {
        when(shifts.periodIdFor(any())).thenReturn(Optional.empty());
        ShiftRequest request = new ShiftRequest("Ca level", day(0), t(8, 0), t(12, 0), 0, null, "level", 1, null, null,
                null, Map.of("lvl1", 1), null, null, null, null, null);
        when(shifts.findById(anyString())).thenReturn(Optional.of(detail("shift_y", request, 1)));

        assertThat(service.create(request, "admin").id()).isEqualTo("shift_y");
    }

    @Test
    void create_withNamedUsersOutsideAnyPeriod_isRejected() {
        when(shifts.periodIdFor(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(userShift(day(0), Set.of("u1")), "admin"))
                .isInstanceOfSatisfying(ShiftRejectedException.class, e ->
                        assertThat(e.errors()).anyMatch(m -> m.contains("không thuộc kỳ nào")));
    }

    @Test
    void create_rejectsUnknownCatalogIds() {
        when(shifts.missingIds(eq("skills"), any())).thenReturn(List.of("skl_ghost"));
        ShiftRequest request = new ShiftRequest("Ca level", day(0), t(8, 0), t(12, 0), 0, null, "level", 1, null, null,
                null, Map.of("lvl1", 1), null, null, Set.of("skl_ghost"), null, null);

        assertThatThrownBy(() -> service.create(request, "admin"))
                .isInstanceOfSatisfying(ShiftRejectedException.class, e ->
                        assertThat(e.errors()).anyMatch(m -> m.contains("Kỹ năng không tồn tại")));
        verify(shifts, never()).insert(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void update_withStaleVersion_throwsConflictBeforeWriting() {
        ShiftRequest request = userShift(day(0), Set.of());
        when(shifts.findById("s1")).thenReturn(Optional.of(detail("s1", request, 2)));

        assertThatThrownBy(() -> service.update("s1", 1, request, "admin"))
                .isInstanceOf(ShiftVersionConflictException.class);
        verify(shifts, never()).update(anyString(), anyInt(), any());
    }

    @Test
    void update_losingTheRaceOnTheConditionalUpdate_throwsConflict() {
        ShiftRequest request = new ShiftRequest("Ca level", day(0), t(8, 0), t(12, 0), 0, null, "level", 1, null, null,
                null, Map.of("lvl1", 1), null, null, null, null, null);
        when(shifts.findById("s1")).thenReturn(Optional.of(detail("s1", request, 1)));
        when(shifts.update("s1", 1, request)).thenReturn(false);

        assertThatThrownBy(() -> service.update("s1", 1, request, "admin"))
                .isInstanceOf(ShiftVersionConflictException.class);
    }

    @Test
    void cancel_requiresAReason() {
        assertThatThrownBy(() -> service.cancel("s1", 1, "  ", "admin")).isInstanceOf(IllegalArgumentException.class);
        verify(shifts, never()).cancel(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void cancel_recordsHistoryWithReason_andReportsAffectedUsers() {
        ShiftRequest request = userShift(day(0), Set.of());
        when(shifts.findById("s1")).thenReturn(Optional.of(detail("s1", request, 3)));
        when(shifts.confirmedAssignees("s1")).thenReturn(Set.of("u2", "u1"));
        when(shifts.cancel("s1", 3, "admin", "Nhân viên báo ốm")).thenReturn(2);

        CancelResult result = service.cancel("s1", 3, "Nhân viên báo ốm", "admin");

        assertThat(result.affectedUserIds()).containsExactly("u1", "u2");
        verify(shifts).writeHistory(eq("shift_cancel"), anyString(), anyString(), eq("admin"), any(), any(),
                eq("Nhân viên báo ốm"));
    }

    @Test
    void cancel_alreadyCancelledShift_isRejected() {
        ShiftRequest request = userShift(day(0), Set.of());
        when(shifts.findById("s1")).thenReturn(Optional.of(new ShiftDetail("s1", request, "not_scheduled", 2, false,
                null, java.time.Instant.now(), "admin", "đã hủy")));

        assertThatThrownBy(() -> service.cancel("s1", 2, "lại hủy", "admin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emergency_requiresAReason_andIsFlaggedAndAudited() {
        ShiftRequest request = new ShiftRequest("Ca gấp", day(0), t(8, 0), t(12, 0), 0, null, "level", 1, null, null,
                null, Map.of("lvl1", 1), null, null, null, null, null);
        assertThatThrownBy(() -> service.addEmergency(request, "", "admin")).isInstanceOf(IllegalArgumentException.class);

        when(shifts.findById(anyString())).thenReturn(Optional.of(detail("shift_e", request, 1)));
        service.addEmergency(request, "Thiếu người đột xuất", "admin");

        verify(shifts).insert(anyString(), eq(request), eq(true), any());
        verify(shifts).writeHistory(eq("shift_emergency_create"), anyString(), any(), eq("admin"), any(), any(),
                eq("Thiếu người đột xuất"));
    }

    @Test
    void describe_keepsTheShiftIdAndSummarisesTimeBreakLocationAndStaffing() {
        ShiftRequest night = new ShiftRequest("Ca đêm", day(0), t(22, 0), t(6, 0), 30, "loc1", "level", 3, 2, 4, null,
                Map.of("lvl1", 3), null, null, null, null, null);

        assertThat(ShiftService.describe(detail("s9", night, 1)))
                .isEqualTo("s9: " + day(0) + " 22:00-06:00 (qua đêm), nghỉ 30 phút, địa điểm loc1, cần 3 người"
                        + " (tối thiểu 2, tối đa 4).");
        assertThat(ShiftService.describe(detail("s1", userShift(day(0), Set.of()), 1)))
                .isEqualTo("s1: " + day(0) + " 08:00-12:00, cần 1 người.");
    }

    @Test
    void create_writesADescriptiveHistoryDetail() {
        ShiftRequest request = new ShiftRequest("Ca level", day(0), t(8, 0), t(12, 0), 0, null, "level", 1, null, null,
                null, Map.of("lvl1", 1), null, null, null, null, null);
        when(shifts.findById(anyString())).thenReturn(Optional.of(detail("shift_x", request, 1)));

        service.create(request, "admin");

        verify(shifts).writeHistory(eq("shift_create"), anyString(), eq("shift_x: " + day(0) + " 08:00-12:00, cần 1 người."),
                eq("admin"), any(), any(), any());
    }

    @Test
    void delete_isRefusedWhenTheShiftHasAssignmentsOrDrafts() {
        ShiftRequest request = userShift(day(0), Set.of());
        when(shifts.findById("s1")).thenReturn(Optional.of(detail("s1", request, 1)));
        when(shifts.countReferences("s1")).thenReturn(2);

        assertThatThrownBy(() -> service.delete("s1", "admin")).isInstanceOf(IllegalArgumentException.class);
        verify(shifts, never()).delete(anyString());
    }

    @Test
    void cloneTo_createsOneShiftPerDistinctDate_withoutCopyingAssignments() {
        ShiftRequest source = new ShiftRequest("Ca level", day(0), t(8, 0), t(12, 0), 0, null, "level", 1, null, null,
                null, Map.of("lvl1", 1), null, null, null, null, null);
        when(shifts.findById("s1")).thenReturn(Optional.of(detail("s1", source, 1)));
        when(shifts.findById(org.mockito.AdditionalMatchers.not(eq("s1"))))
                .thenReturn(Optional.of(detail("shift_copy", source, 1)));

        List<ShiftDetail> copies = service.cloneTo("s1", List.of(day(1), day(2), day(1)), "admin");

        assertThat(copies).hasSize(2);
        verify(shifts, times(2)).insert(anyString(), any(), eq(false), any());
    }

    @Test
    void repeatDates_everyWeekAndEveryTwoWeeks() {
        LocalDate monday = day(0);
        assertThat(ShiftService.repeatDates(monday, monday.plusDays(21), Set.of(1), 1))
                .containsExactly(monday.plusDays(7), monday.plusDays(14), monday.plusDays(21));
        assertThat(ShiftService.repeatDates(monday, monday.plusDays(21), Set.of(1), 2))
                .containsExactly(monday.plusDays(14));
        assertThat(ShiftService.repeatDates(monday, monday.plusDays(7), Set.of(2, 4), 1))
                .containsExactly(monday.plusDays(1), monday.plusDays(3));
    }

    @Test
    void repeatDates_rejectsBadInput() {
        LocalDate monday = day(0);
        assertThatThrownBy(() -> ShiftService.repeatDates(monday, monday, Set.of(1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftService.repeatDates(monday, monday.plusDays(7), Set.of(8), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftService.repeatDates(monday, monday.plusDays(7), Set.of(), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftService.repeatDates(monday, monday.plusDays(7), Set.of(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftService.repeatDates(monday, monday.plusDays(400), Set.of(1), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createMany_isAllOrNothing_whenAnyConfigIsInvalid() {
        ShiftRequest ok = new ShiftRequest("Ca ok", day(0), t(8, 0), t(12, 0), 0, null, "level", 1, null, null,
                null, Map.of("lvl1", 1), null, null, null, null, null);
        ShiftRequest bad = new ShiftRequest(" ", day(1), t(8, 0), t(12, 0), 0, null, "level", 1, null, null,
                null, Map.of("lvl1", 1), null, null, null, null, null);

        assertThatThrownBy(() -> service.createMany(List.of(ok, bad), "admin"))
                .isInstanceOfSatisfying(ShiftRejectedException.class, e ->
                        assertThat(e.errors()).anyMatch(m -> m.startsWith("Ca #2:")));
        verify(shifts, never()).insert(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }
}
