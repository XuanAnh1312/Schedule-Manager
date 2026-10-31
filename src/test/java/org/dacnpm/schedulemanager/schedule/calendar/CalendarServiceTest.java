package org.dacnpm.schedulemanager.schedule.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarRepository.AssignmentRow;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarRepository.Person;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarRepository.PublishedVersion;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarService.CalendarShift;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarService.CalendarView;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarService.MySchedule;
import org.dacnpm.schedulemanager.schedule.calendar.CalendarService.Query;
import org.dacnpm.schedulemanager.schedule.plan.PlanException;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanService;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TW-05: lịch ngày/tuần/tháng, bộ lọc, phương án chồng lên; nhân viên chỉ thấy lịch đã công bố của mình. */
class CalendarServiceTest {

    private final ShiftRepository shifts = mock(ShiftRepository.class);
    private final CalendarRepository repository = mock(CalendarRepository.class);
    private final PlanService plans = mock(PlanService.class);
    private CalendarService service;

    private static ShiftDetail detail(String id, LocalDate date, String status, Map<String, Integer> levels) {
        ShiftRequest r = new ShiftRequest("Ca " + id, date, t(8, 0), t(12, 0), 30, "loc1", levels == null ? "user" : "level",
                1, null, null, null, levels, null, null, null, null, null);
        return new ShiftDetail(id, r, status, 1, false, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        service = new CalendarService(shifts, repository, plans,
                java.time.Clock.fixed(java.time.Instant.parse("2026-01-07T03:00:00Z"), java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        when(shifts.search(any(), any(), any(), anyBoolean())).thenReturn(List.of(
                detail("s0", day(0), "published", null),
                detail("s1", day(1), "draft", Map.of("lvl2", 1)),
                detail("s2", day(2), "published", null)));
        when(repository.confirmedAssignments(any(), any())).thenReturn(List.of(
                new AssignmentRow("a0", "s0", "u1", "An", "lvl1", 1),
                new AssignmentRow("a1", "s1", "u1", "An", "lvl1", 1),
                new AssignmentRow("a2", "s2", "u2", "Bình", "lvl2", 1)));
    }

    @Test
    void employeeSeesOnlyTheirOwnPublishedShifts() {
        when(repository.publishedVersionsOf(eq("u1"), any(), any())).thenReturn(List.of(
                new PublishedVersion("plan_1", "Tháng 1", "p1", LocalDateTime.now(), null, null)));

        MySchedule mine = service.mine("u1", "week", day(0), null, null);

        assertThat(mine.shifts()).singleElement().satisfies(s -> {
            assertThat(s.shiftId()).isEqualTo("s0");
            assertThat(s.hours()).isEqualTo(3.5); // 4 giờ trừ 30 phút nghỉ
        });
        assertThat(mine.needsConfirmation()).isTrue();
    }

    @Test
    void acknowledgingRequiresAPublishedScheduleThatIncludesTheEmployee() {
        when(repository.isPublishedFor("plan_1", "u1")).thenReturn(false);
        assertThatThrownBy(() -> service.acknowledge("u1", "plan_1", true))
                .isInstanceOf(PlanException.class)
                .satisfies(e -> assertThat(((PlanException) e).code()).isEqualTo(PlanException.NOT_FOUND));
        verify(repository, never()).acknowledge(anyString(), anyString(), anyBoolean());

        when(repository.isPublishedFor("plan_1", "u1")).thenReturn(true);
        service.acknowledge("u1", "plan_1", true);
        verify(repository).acknowledge("u1", "plan_1", true);
    }

    @Test
    void adminCalendar_filtersByStatusEmployeeAndLevel() {
        Query base = new Query("week", day(0), null, null, null, null, null, null, null, null);

        assertThat(service.calendar(base).shifts()).hasSize(3);
        assertThat(service.calendar(new Query("week", day(0), null, null, null, null, null, null, "draft", null)).shifts())
                .extracting(CalendarShift::shiftId).containsExactly("s1");
        assertThat(service.calendar(new Query("week", day(0), null, null, null, null, "u2", null, null, null)).shifts())
                .extracting(CalendarShift::shiftId).containsExactly("s2");
        assertThat(service.calendar(new Query("week", day(0), null, null, null, null, null, "lvl2", null, null)).shifts())
                .extracting(CalendarShift::shiftId).containsExactly("s1", "s2");
        assertThat(service.calendar(new Query("week", day(0), null, null, null, null, null, null, "full", null)).shifts())
                .hasSize(3);
    }

    @Test
    void adminCalendar_canOverlayADraftPlanOnItsShifts() {
        PlanView plan = new PlanView("plan_9", "p1", "Nháp", "draft", 1, 1, "admin", null, null, null, List.of("s1"), null,
                List.of(new PlanPair("s1", "u2")));
        when(plans.get("plan_9")).thenReturn(plan);
        when(repository.people(any())).thenReturn(Map.of("u2", new Person("u2", "Bình", "lvl2")));

        CalendarView view = service.calendar(new Query("week", day(0), null, null, null, null, null, null, null, "plan_9"));

        CalendarShift s1 = view.shifts().stream().filter(s -> s.shiftId().equals("s1")).findFirst().orElseThrow();
        assertThat(s1.source()).isEqualTo("plan_9");
        assertThat(s1.assignees()).extracting(CalendarService.Assignee::userId).containsExactly("u2");
        CalendarShift s0 = view.shifts().stream().filter(s -> s.shiftId().equals("s0")).findFirst().orElseThrow();
        assertThat(s0.source()).isEqualTo("official");
    }

    @Test
    void dayWeekAndMonthRanges() {
        LocalDate wednesday = day(2);
        assertThat(CalendarService.range("day", wednesday, null, null)).containsExactly(wednesday, wednesday);
        assertThat(CalendarService.range("week", wednesday, null, null)).containsExactly(day(0), day(6));
        assertThat(CalendarService.range("month", wednesday, null, null))
                .containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertThatThrownBy(() -> CalendarService.range("year", wednesday, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarService.range(null, null, day(0), day(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
