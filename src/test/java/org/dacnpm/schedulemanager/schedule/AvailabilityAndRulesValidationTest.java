package org.dacnpm.schedulemanager.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.model.TimeRange;
import org.dacnpm.schedulemanager.schedule.model.UserAvailability;
import org.dacnpm.schedulemanager.schedule.rules.EffectiveRules;
import org.dacnpm.schedulemanager.schedule.rules.EmployeeRules;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCandidate;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.junit.jupiter.api.Test;

/**
 * TW-R2: khoảng không thể làm và nghỉ đã duyệt là ràng buộc cứng, ngày không có slot không phải rảnh cả ngày, slot qua
 * đêm được tính đúng. TW-R1: thiếu tham số bắt buộc của quy tắc hiệu lực thì báo đúng tham số, không tự điền.
 */
class AvailabilityAndRulesValidationTest {

    private static final List<Employee> STAFF = List.of(employee("u1", "lvl1", 100.0));
    private static final TimeRange WHOLE_DAY = new TimeRange(0, 1440);

    private final AssignmentValidator validator = new AssignmentValidator();

    private ValidationResult check(UserAvailability availability, ShiftInfo shift) {
        ScheduleContext ctx = new ScheduleContext(PERIOD, rules(12, 60.0), STAFF, List.of(shift),
                Map.of("u1", availability), Map.of(), List.of(), Set.of());
        return validator.validate(ctx, new AssignmentCandidate("u1", shift.id(), null));
    }

    private static UserAvailability registration(Map<LocalDate, List<TimeRange>> available,
                                                 Map<LocalDate, List<TimeRange>> unavailable) {
        return UserAvailability.subtract("u1", true, available, unavailable);
    }

    private static TimeRange range(int fromHour, int toHour) {
        return TimeRange.of(t(fromHour, 0), t(toHour, 0));
    }

    @Test
    void unavailableIntervalInsideAnAvailableDay_isAHardBlock() {
        UserAvailability a = registration(Map.of(day(0), List.of(range(8, 17))), Map.of(day(0), List.of(range(12, 14))));

        ValidationResult inside = check(a, shift("s1", day(0), t(12, 0), t(14, 0), 0, 1, "user"));
        ValidationResult overlapping = check(a, shift("s2", day(0), t(13, 0), t(16, 0), 0, 1, "user"));

        assertThat(inside.has(ViolationCode.UNAVAILABLE_INTERVAL)).isTrue();
        assertThat(inside.overridable()).isFalse();
        assertThat(overlapping.has(ViolationCode.UNAVAILABLE_INTERVAL)).isTrue();
        assertThat(check(a, shift("s3", day(0), t(8, 0), t(12, 0), 0, 1, "user")).valid()).isTrue();
    }

    @Test
    void dayWithoutAnySlot_isNotTreatedAsFreeAllDay() {
        UserAvailability a = registration(Map.of(day(0), List.of(range(8, 17))), Map.of());

        ValidationResult result = check(a, shift("s1", day(1), t(8, 0), t(12, 0), 0, 1, "user"));

        assertThat(result.has(ViolationCode.NOT_AVAILABLE_TIME)).isTrue();
    }

    @Test
    void overnightSlot_coversAnOvernightShift_andAnOvernightUnavailableIntervalBlocksIt() {
        ShiftInfo night = shift("n1", day(0), t(22, 0), t(2, 0), 0, 1, "user");
        UserAvailability free = registration(Map.of(day(0), List.of(range(22, 2))), Map.of());
        UserAvailability blocked = registration(Map.of(day(0), List.of(range(22, 2))), Map.of(day(0), List.of(range(23, 1))));

        assertThat(check(free, night).valid()).isTrue();
        assertThat(check(blocked, night).has(ViolationCode.UNAVAILABLE_INTERVAL)).isTrue();
        // Phần sau nửa đêm của khoảng không thể làm thuộc ngày hôm sau.
        assertThat(check(blocked, shift("n2", day(1), t(0, 30), t(1, 30), 0, 1, "user"))
                .has(ViolationCode.UNAVAILABLE_INTERVAL)).isTrue();
    }

    @Test
    void approvedLeave_blocksEvenWhenTheEmployeeRegisteredAsAvailable() {
        // ScheduleDataRepository đưa nghỉ đã duyệt vào danh sách không thể làm cả ngày.
        UserAvailability a = registration(Map.of(day(2), List.of(range(6, 22))), Map.of(day(2), List.of(WHOLE_DAY)));

        ValidationResult result = check(a, shift("s1", day(2), t(8, 0), t(12, 0), 0, 1, "user"));

        assertThat(result.has(ViolationCode.UNAVAILABLE_INTERVAL)).isTrue();
        assertThat(result.overridable()).isFalse();
    }

    @Test
    void missingRequiredRules_areReportedPerParameter_andNothingIsAssumed() {
        EmployeeRules notReady = new EmployeeRules(0, 0, null, 0, null, null, null, null, null,
                List.of(new EmployeeRules.MissingParameter("max_hours_per_week", "Giờ tối đa mỗi tuần", "Chưa cấu hình."),
                        new EmployeeRules.MissingParameter("min_rest_hours", "Nghỉ tối thiểu", "Chưa cấu hình.")));
        EffectiveRules source = (userId, date) -> notReady;
        ShiftInfo s1 = shift("s1", day(0), t(8, 0), t(12, 0), 0, 1, "user");
        ScheduleContext ctx = new ScheduleContext(PERIOD, rules(12, 60.0), STAFF, List.of(s1), freeAllPeriod(STAFF),
                Map.of(), List.of(), Set.of(), source);

        ValidationResult result = validator.validate(ctx, new AssignmentCandidate("u1", "s1", null));

        assertThat(result.violations()).extracting(v -> v.code() + ":" + v.parameter())
                .containsExactly("MISSING_PARAMETER:max_hours_per_week", "MISSING_PARAMETER:min_rest_hours");
    }

    @Test
    void contractLimitTighterThanTheCompanyRule_stillBlocks() {
        EmployeeRules effective = new EmployeeRules(12, 8, null, 11, 6, null, "hợp đồng 8 giờ/tuần", null, null, List.of());
        EffectiveRules source = (userId, date) -> effective;
        ShiftInfo s1 = shift("s1", day(0), t(8, 0), t(18, 0), 0, 1, "user");
        ScheduleContext ctx = new ScheduleContext(PERIOD, rules(12, 60.0), STAFF, List.of(s1), freeAllPeriod(STAFF),
                Map.of(), List.of(), Set.of(), source);

        assertThat(validator.validate(ctx, new AssignmentCandidate("u1", "s1", null)).has(ViolationCode.HOURS_PER_WEEK))
                .isTrue();
    }
}
