package org.dacnpm.schedulemanager.schedule.official;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.dacnpm.schedulemanager.schedule.events.ScheduleEvent;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService.Preview;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleService.ChangeResult;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleService.ExceptionOverride;
import org.dacnpm.schedulemanager.schedule.plan.PlanException;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository;
import org.dacnpm.schedulemanager.schedule.plan.PlanService;
import org.dacnpm.schedulemanager.schedule.plan.RosterChecker;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftDetail;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRequest;
import org.dacnpm.schedulemanager.schedule.support.FakeExceptions;
import org.dacnpm.schedulemanager.schedule.support.FakeOfficialSchedule;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TW-06: thay/thêm/hủy phân công trên lịch chính thức với kiểm tra, ngoại lệ, khóa lạc quan và sự kiện. */
class OfficialScheduleServiceTest {

    private final FakeOfficialSchedule official = new FakeOfficialSchedule();
    private final FakeExceptions exceptionRepo = new FakeExceptions();
    private final ShiftRepository shifts = mock(ShiftRepository.class);
    private final ScheduleDataRepository data = mock(ScheduleDataRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final List<ScheduleEvent> events = new ArrayList<>();
    private final List<Employee> staff = List.of(employee("u1", "lvl1", 100.0), employee("u2", "lvl1", 100.0),
            employee("u3", "lvl1", 100.0));
    private final Map<String, Set<LocalDate>> blocked = new HashMap<>();
    private final List<ShiftInfo> shiftInfos = List.of(
            shift("s0", day(0), t(8, 0), t(12, 0), 0, 1, "user"),
            shift("s1", day(0), t(10, 0), t(14, 0), 0, 1, "user"),
            shift("s2", day(2), t(8, 0), t(12, 0), 0, 2, "user"));
    private OfficialScheduleService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AssignmentValidator validator = new AssignmentValidator();
        ExceptionService exceptions = new ExceptionService(exceptionRepo, plans, mock(PlanService.class), data,
                new RosterChecker(validator), shifts, mapper);
        service = new OfficialScheduleService(official, shifts, data, validator, exceptions, plans, events::add);
        for (ShiftInfo s : shiftInfos) {
            ShiftRequest r = new ShiftRequest(s.name(), s.date(), s.startTime(), s.endTime(), 0, null, "user",
                    s.totalRequired(), null, null, null, null, null, null, null, null, null);
            when(shifts.findById(s.id())).thenReturn(Optional.of(new ShiftDetail(s.id(), r, "published", 1, false,
                    null, null, null, null)));
        }
        when(shifts.periodIdFor(any())).thenReturn(Optional.of("p1"));
        when(data.load("p1")).thenAnswer(inv -> Optional.of(new ScheduleData(PERIOD, rules(12, 60.0), staff, shiftInfos,
                List.of("s0", "s1", "s2"), freeAllPeriod(staff), official.asAssignments(), blocked)));
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(PlanException.class)
                .satisfies(e -> assertThat(((PlanException) e).code()).isEqualTo(code));
    }

    @Test
    void reassign_replacesTheHolder_andNotifiesBothEmployees() {
        String id = official.put("s0", "u1");

        ChangeResult result = service.reassign(id, "u1", "u2", "admin", "Đổi ca", null);

        assertThat(official.confirmed()).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo("u2"));
        assertThat(result.exceptionId()).isNull();
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(ScheduleEvent.SCHEDULE_CHANGED);
            assertThat(e.eventId()).isEqualTo("schedule.changed:" + id + ":v2");
            assertThat(e.recipients()).containsExactly("u1", "u2");
        });
    }

    @Test
    void reassign_whenTheHolderChangedMeanwhile_isAConflictAndChangesNothing() {
        String id = official.put("s0", "u1");

        assertCode(() -> service.reassign(id, "u3", "u2", "admin", "Đổi ca", null), PlanException.VERSION_CONFLICT);
        assertThat(official.confirmed()).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo("u1"));
        assertThat(events).isEmpty();
    }

    @Test
    void reassign_neverOverridesAHardConstraint() {
        String id = official.put("s0", "u1");
        official.put("s1", "u2"); // u2 đã có ca trùng giờ

        assertCode(() -> service.reassign(id, "u1", "u2", "admin", "Đổi ca",
                new ExceptionOverride("Gấp", "x", true)), PlanException.HARD_VIOLATIONS);
        assertThat(official.find(id).orElseThrow().confirmed()).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    void addAssignment_withAnExceptionableViolation_needsPermissionPreviewAndConfirmation() {
        blocked.put("u3", Set.of(day(2)));

        assertCode(() -> service.addAssignment("s2", "u3", "admin", "Thiếu người", null),
                PlanException.EXCEPTION_REQUIRED);
        assertCode(() -> service.preview("s2", "u3", null, "admin"), PlanException.FORBIDDEN);

        exceptionRepo.permitted.add("admin");
        Preview preview = service.preview("s2", "u3", null, "admin");
        assertThat(preview.allowed()).isTrue();
        assertThat(preview.exceptionable()).anyMatch(v -> v.code() == ViolationCode.UNAVAILABLE);

        ChangeResult result = service.addAssignment("s2", "u3", "admin", "Thiếu người",
                new ExceptionOverride("Nhân viên đồng ý đi làm", preview.previewToken(), true));

        assertThat(result.exceptionId()).isNotNull();
        assertThat(exceptionRepo.records).singleElement().satisfies(r -> {
            assertThat(r.planId()).isNull();
            assertThat(r.reason()).isEqualTo("Nhân viên đồng ý đi làm");
        });
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(ScheduleEvent.ASSIGNMENT_ADDED);
            assertThat(e.recipients()).containsExactly("u3");
        });
    }

    @Test
    void cancelAssignment_requiresAReason_andWarnsWhenTheShiftBecomesUnderstaffed() {
        String id = official.put("s0", "u1");
        assertThatThrownBy(() -> service.cancelAssignment(id, "u1", "admin", " "))
                .isInstanceOf(IllegalArgumentException.class);

        ChangeResult result = service.cancelAssignment(id, "u1", "admin", "Nghỉ phép được duyệt");

        assertThat(official.confirmed()).isEmpty();
        assertThat(result.warnings()).singleElement().satisfies(w -> assertThat(w).contains("0/1"));
        assertThat(events).singleElement().satisfies(e -> assertThat(e.recipients()).containsExactly("u1"));
    }

    @Test
    void validate_forTheRequestModule_honoursAssignmentsBeingRemovedInTheSameOperation() {
        String s0 = official.put("s0", "u1");

        assertThat(service.validate("u1", "s1", null, Set.of(), null).has(ViolationCode.OVERLAP)).isTrue();
        assertThat(service.validate("u1", "s1", null, Set.of(s0), null).valid()).isTrue();
    }

    @Test
    void everyChangeLocksThePeriodFirst() {
        String id = official.put("s0", "u1");

        service.cancelAssignment(id, "u1", "admin", "Lý do");

        org.mockito.Mockito.verify(plans).lockPeriod("p1");
        org.mockito.Mockito.verify(shifts).writeHistory(org.mockito.ArgumentMatchers.eq("manual_unassign"), anyString(),
                anyString(), org.mockito.ArgumentMatchers.eq("admin"), any(), any(), anyString());
    }
}
