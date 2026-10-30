package org.dacnpm.schedulemanager.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dacnpm.schedulemanager.schedule.ScheduleFixtures.*;

import java.util.List;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCandidate;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.junit.jupiter.api.Test;

class PositionValidationTest {

    private final AssignmentValidator validator = new AssignmentValidator();

    private static ShiftInfo shiftNeeding(Set<String> positions) {
        return new ShiftInfo("s0", "Ca s0", day(0), t(8, 0), t(12, 0), 0, "user", 1, null, null, null, null,
                null, null, null, positions);
    }

    private static Employee holding(String id, Set<String> positions) {
        return new Employee(id, id, true, "lvl1", 100.0, null, null, null, null, positions);
    }

    @Test
    void employeeMustHoldAtLeastOneRequiredPosition() {
        Employee cashier = holding("u1", Set.of("pos_cashier"));
        Employee none = holding("u2", Set.of());
        ShiftInfo shift = shiftNeeding(Set.of("pos_cashier", "pos_lead"));
        ScheduleContext ctx = context(rules(8, 60.0), List.of(cashier, none), List.of(shift), List.of());

        assertThat(validator.validate(ctx, new AssignmentCandidate("u1", "s0", null)).valid()).isTrue();
        var rejected = validator.validate(ctx, new AssignmentCandidate("u2", "s0", null));
        assertThat(rejected.has(ViolationCode.POSITION)).isTrue();
        assertThat(rejected.overridable()).isFalse();
    }

    @Test
    void shiftWithoutPositionRequirement_acceptsAnyone() {
        Employee none = holding("u2", Set.of());
        ScheduleContext ctx = context(rules(8, 60.0), List.of(none), List.of(shiftNeeding(Set.of())), List.of());
        assertThat(validator.validate(ctx, new AssignmentCandidate("u2", "s0", null)).valid()).isTrue();
    }
}
