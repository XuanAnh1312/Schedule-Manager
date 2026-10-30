package org.dacnpm.schedulemanager.schedule.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCandidate;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.Violation;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.springframework.stereotype.Component;

/**
 * Kiểm tra toàn bộ phân công (roster) của một phương án bằng cùng {@link AssignmentValidator} với phân lịch tự động.
 * Ngữ cảnh = lịch chính thức trừ phân công của các ca thuộc phương án (sẽ được thay khi công bố), cộng roster của
 * phương án; mỗi cặp được kiểm tra với chính nó được bỏ ra.
 */
@Component
public class RosterChecker {

    /** Vi phạm của một cặp ca–nhân viên, tách bắt buộc và có thể xin ngoại lệ. */
    public record PairCheck(String shiftId, String userId, List<Violation> hard, List<Violation> exceptionable) {
        public boolean clean() {
            return hard.isEmpty() && exceptionable.isEmpty();
        }

        public Set<String> exceptionCodes() {
            return exceptionable.stream().map(v -> v.code().name()).collect(Collectors.toSet());
        }
    }

    public record ShiftCoverage(String shiftId, String name, int required, int minimum, int filled, String coverage) {
    }

    public record RosterCheck(List<PairCheck> pairs, List<ShiftCoverage> shifts) {
        public List<PairCheck> withHardViolations() {
            return pairs.stream().filter(p -> !p.hard().isEmpty()).toList();
        }

        public List<PairCheck> withExceptionViolations() {
            return pairs.stream().filter(p -> p.hard().isEmpty() && !p.exceptionable().isEmpty()).toList();
        }

        public List<ShiftCoverage> understaffed() {
            return shifts.stream().filter(s -> EngineResult.UNDERSTAFFED.equals(s.coverage())).toList();
        }
    }

    private final AssignmentValidator validator;

    public RosterChecker(AssignmentValidator validator) {
        this.validator = validator;
    }

    /**
     * Ngữ cảnh của phương án: phân công chính thức trên các ca của phương án được coi là bị thay thế, roster được thêm
     * vào như đề xuất (trừ {@code excluded}).
     */
    public static ScheduleContext planContext(ScheduleData d, Collection<String> planShiftIds, List<PlanPair> roster,
                                              PlanPair excluded) {
        Set<String> planShifts = Set.copyOf(planShiftIds);
        Set<String> replaced = d.savedAssignments().stream()
                .filter(a -> planShifts.contains(a.shiftId()))
                .map(Assignment::id)
                .collect(Collectors.toSet());
        ScheduleContext ctx = d.context(replaced);
        int i = 0;
        for (PlanPair pair : roster) {
            if (!pair.equals(excluded) && ctx.shift(pair.shiftId()) != null) {
                ctx.addProposed(new Assignment("roster_" + (i++), pair.shiftId(), pair.userId(), Assignment.CONFIRMED));
            }
        }
        return ctx;
    }

    /** Kiểm tra một cặp (ca, nhân viên) so với phần còn lại của phương án. */
    public ValidationResult checkPair(ScheduleData d, Collection<String> planShiftIds, List<PlanPair> roster,
                                      PlanPair pair, String requiredLevelId) {
        ScheduleContext ctx = planContext(d, planShiftIds, roster, pair);
        if (ctx.shift(pair.shiftId()) == null) {
            return new ValidationResult(List.of(new Violation(ViolationCode.SHIFT_CANCELLED, pair.userId(), pair.shiftId(),
                    null, null, null, "Ca " + pair.shiftId() + " đã hủy hoặc không còn thuộc kỳ.")));
        }
        return validator.validate(ctx, new AssignmentCandidate(pair.userId(), pair.shiftId(), requiredLevelId));
    }

    public RosterCheck check(ScheduleData d, Collection<String> planShiftIds, List<PlanPair> roster) {
        List<PairCheck> pairs = new ArrayList<>();
        for (PlanPair pair : roster) {
            ValidationResult result = checkPair(d, planShiftIds, roster, pair, null);
            List<Violation> hard = result.violations().stream().filter(v -> !v.code().overridable()).toList();
            List<Violation> soft = result.violations().stream().filter(v -> v.code().overridable()).toList();
            pairs.add(new PairCheck(pair.shiftId(), pair.userId(), hard, soft));
        }
        ScheduleContext ctx = planContext(d, planShiftIds, roster, null);
        List<ShiftCoverage> coverage = new ArrayList<>();
        for (String shiftId : planShiftIds) {
            ShiftInfo shift = ctx.shift(shiftId);
            if (shift == null) {
                continue;
            }
            int filled = (int) roster.stream().filter(p -> p.shiftId().equals(shiftId)).count();
            String level = filled >= shift.totalRequired() ? EngineResult.FULL
                    : shift.understaffedWith(filled) ? EngineResult.UNDERSTAFFED : EngineResult.BELOW_TARGET;
            coverage.add(new ShiftCoverage(shiftId, shift.name(), shift.totalRequired(), shift.minimum(), filled, level));
        }
        return new RosterCheck(pairs, coverage);
    }
}
