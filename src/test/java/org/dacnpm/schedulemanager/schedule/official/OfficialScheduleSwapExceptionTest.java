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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** BAO-02: đổi chéo có ngoại lệ từng vị trí (xem trước theo trạng thái cuối) và khối lượng giờ cho ca trống tự chọn. */
class OfficialScheduleSwapExceptionTest {

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
                    s.totalRequired(), null, null, null, null, null, null, null, null, null, "p1");
            when(shifts.findById(s.id())).thenReturn(Optional.of(new ShiftDetail(s.id(), r, "published", 1, false,
                    null, null, null, null)));
        }
        when(data.load("p1")).thenAnswer(inv -> Optional.of(new ScheduleData(PERIOD, rules(12, 60.0), staff, shiftInfos,
                List.of("s0", "s1", "s2"), freeAllPeriod(staff), official.asAssignments(), blocked)));
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(PlanException.class)
                .satisfies(e -> assertThat(((PlanException) e).code()).isEqualTo(code));
    }

    @Test
    void previewSwap_matchesTheFinalStateCheck_andSwapWithTheTokenRecordsTheException() {
        String a = official.put("s0", "u1");
        String b = official.put("s2", "u2");
        blocked.put("u2", Set.of(day(0))); // u2 không thể làm ngày của s0 → thuộc nhóm ngoại lệ
        exceptionRepo.permitted.add("admin");

        List<Preview> previews = service.previewSwap(a, "u1", b, "u2", "admin");

        assertThat(previews).hasSize(2);
        assertThat(previews.get(0).allowed()).isTrue();
        assertThat(previews.get(0).exceptionable()).anyMatch(v -> v.code() == ViolationCode.UNAVAILABLE);
        assertThat(previews.get(1).exceptionNeeded()).isFalse();

        service.swap(a, "u1", b, "u2", "admin", "Duyệt yêu cầu req9",
                new ExceptionOverride("Nhân viên tự nguyện", previews.get(0).previewToken(), true), null, false);

        assertThat(official.confirmed()).extracting(x -> x.shiftId() + ":" + x.userId())
                .containsExactlyInAnyOrder("s0:u2", "s2:u1");
        assertThat(exceptionRepo.records).singleElement().satisfies(r -> {
            assertThat(r.shiftId()).isEqualTo("s0");
            assertThat(r.userId()).isEqualTo("u2");
            assertThat(r.reason()).isEqualTo("Nhân viên tự nguyện");
        });
        assertThat(events).isEmpty(); // notify = false: module đổi ca tự gửi thông báo nghiệp vụ
    }

    @Test
    void swapWithAStaleToken_isRejected_andChangesNothing() {
        String a = official.put("s0", "u1");
        String b = official.put("s2", "u2");
        blocked.put("u2", Set.of(day(0)));
        exceptionRepo.permitted.add("admin");

        assertCode(() -> service.swap(a, "u1", b, "u2", "admin", "Duyệt",
                new ExceptionOverride("Lý do", "token-cu", true), null, false), PlanException.STALE_PREVIEW);
        assertThat(official.confirmed()).extracting(x -> x.shiftId() + ":" + x.userId())
                .containsExactlyInAnyOrder("s0:u1", "s2:u2");
        assertThat(exceptionRepo.records).isEmpty();
    }

    @Test
    void previewSwap_withoutExceptionPermission_isForbidden() {
        String a = official.put("s0", "u1");
        String b = official.put("s2", "u2");

        assertCode(() -> service.previewSwap(a, "u1", b, "u2", "nv001"), PlanException.FORBIDDEN);
    }

    @Test
    void workload_sumsHoursInThePeriodOfTheShift() {
        official.put("s0", "u1");
        official.put("s2", "u1");

        OfficialScheduleService.WorkloadSnapshot w = service.workload("u1", "s1");

        assertThat(w.scheduledHours()).isEqualTo(8.0);
        assertThat(service.workload("u3", "s1").scheduledHours()).isZero();
    }
}
