package org.dacnpm.schedulemanager.schedule.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.Decision;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.EmployeeLoad;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.MissingParameterReport;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.PlannedAssignment;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.Rejection;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.ShiftOutcome;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult.Summary;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.rules.EmployeeRules;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentCandidate;
import org.dacnpm.schedulemanager.schedule.validation.AssignmentValidator;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.Violation;
import org.dacnpm.schedulemanager.schedule.validation.ViolationCode;
import org.dacnpm.schedulemanager.schedule.validation.WorkTime;
import org.springframework.stereotype.Component;

/**
 * Thuật toán phân lịch backend (TW-03), heuristic tham lam, không tuyên bố tối ưu toàn cục.
 *
 * <ul>
 *   <li><b>Ràng buộc cứng</b>: mọi ứng viên đều qua {@link AssignmentValidator} với ngữ cảnh gồm phân công đã lưu
 *       (trừ phân công hủy và phần đang được thay thế) cộng phân công vừa sinh trong lần chạy; người được chọn được
 *       kiểm tra lại ngay trước khi chốt. Không tìm được người hợp lệ thì giữ thiếu người, không nới ràng buộc.</li>
 *   <li><b>Tiêu chí mềm</b> chỉ dùng để xếp hạng các ứng viên đã hợp lệ: nằm trong danh sách ưu tiên của ca, đúng
 *       Level yêu cầu, đưa giờ về gần mục tiêu kỳ và chia đều số ca theo trọng số của quy tắc hiệu lực.</li>
 *   <li>Giờ được tính sau khi trừ giờ nghỉ của ca và tách theo ngày lịch, nên ca qua đêm, qua tuần, qua kỳ được tính
 *       đúng phần (xem {@link WorkTime}).</li>
 * </ul>
 *
 * <p>Engine chỉ thêm đề xuất vào {@link ScheduleContext} được truyền vào; không đọc/ghi cơ sở dữ liệu.
 */
@Component
public class ScheduleEngine {

    static final String MANDATORY = "mandatory";
    static final String PREFERRED = "preferred";
    static final String LEVEL = "level";
    static final String FLEX = "flex";

    static final double PREFERRED_BONUS = 50;
    static final double EXACT_LEVEL_BONUS = 10;
    static final double POINTS_PER_SHIFT = 10;

    private final AssignmentValidator validator;

    public ScheduleEngine(AssignmentValidator validator) {
        this.validator = validator;
    }

    /**
     * Phân lịch cho các ca chỉ định.
     *
     * @param ctx      ngữ cảnh kỳ; khi chạy lại phương án, phân công được thay thế phải được loại khỏi ngữ cảnh từ
     *                 trước (replacedAssignmentIds) để giờ không bị đếm hai lần
     * @param shiftIds các ca cần phân, phải có trong ngữ cảnh
     */
    public EngineResult run(ScheduleContext ctx, List<String> shiftIds) {
        if (shiftIds == null || shiftIds.isEmpty()) {
            throw new IllegalArgumentException("Không có ca nào cần phân lịch.");
        }
        List<ShiftInfo> shifts = new ArrayList<>();
        for (String id : new LinkedHashSet<>(shiftIds)) {
            ShiftInfo shift = ctx.shift(id);
            if (shift == null) {
                throw new IllegalArgumentException("Không tìm thấy ca " + id + " trong kỳ.");
            }
            shifts.add(shift);
        }
        return new Run(ctx, validator).execute(shifts);
    }

    /** Một vị trí cần điền trong ca. */
    record Slot(String type, String userId, String levelId) {
        static Slot flex() {
            return new Slot(FLEX, null, null);
        }

        String label() {
            return switch (type) {
                case LEVEL -> "theo Level " + levelId;
                case MANDATORY -> "bắt buộc " + userId;
                case PREFERRED -> "ưu tiên " + userId;
                default -> "tự do";
            };
        }
    }

    private record Scored(Employee employee, double score, List<String> reasons) {
    }

    /** Trạng thái của một lần chạy. */
    private static final class Run {

        private final ScheduleContext ctx;
        private final AssignmentValidator validator;
        private final LocalDate referenceDate;
        private final Map<String, Double> savedHours = new HashMap<>();
        private final Map<String, Integer> savedShifts = new HashMap<>();
        private final Map<String, Double> plannedHours = new HashMap<>();
        private final Map<String, Integer> plannedShifts = new HashMap<>();
        private final List<PlannedAssignment> planned = new ArrayList<>();
        private final Map<String, MissingParameterReport> missing = new LinkedHashMap<>();
        private final Map<String, Long> rejectionsByCode = new TreeMap<>();
        private final List<String> warnings = new ArrayList<>();
        private int unfilled;
        private int sequence;

        Run(ScheduleContext ctx, AssignmentValidator validator) {
            this.ctx = ctx;
            this.validator = validator;
            this.referenceDate = ctx.period() == null ? null : ctx.period().startDate();
            for (Assignment a : ctx.activeAssignments()) {
                ShiftInfo shift = ctx.shiftOf(a);
                if (shift == null) {
                    continue;
                }
                double h = hoursInPeriod(shift);
                if (h > 0) {
                    savedHours.merge(a.userId(), h, Double::sum);
                    savedShifts.merge(a.userId(), 1, Integer::sum);
                }
            }
        }

        EngineResult execute(List<ShiftInfo> shifts) {
            // Ca ít ứng viên hợp lệ nhất được xếp trước để không bị ca dễ "chiếm" mất người.
            Map<String, Integer> eligible = new HashMap<>();
            for (ShiftInfo shift : shifts) {
                int count = 0;
                for (Employee e : ctx.employees()) {
                    if (e.active() && validator.validate(ctx, new AssignmentCandidate(e.id(), shift.id(), null)).valid()) {
                        count++;
                    }
                }
                eligible.put(shift.id(), count);
            }
            List<ShiftInfo> order = new ArrayList<>(shifts);
            order.sort(Comparator.comparingInt((ShiftInfo s) -> eligible.get(s.id()))
                    .thenComparing(ShiftInfo::startAt).thenComparing(ShiftInfo::id));

            List<ShiftOutcome> outcomes = new ArrayList<>();
            for (ShiftInfo shift : order) {
                outcomes.add(planShift(shift));
            }
            outcomes.sort(Comparator.comparing(ShiftOutcome::date).thenComparing(ShiftOutcome::startTime)
                    .thenComparing(ShiftOutcome::shiftId));

            int full = 0;
            int below = 0;
            int under = 0;
            for (ShiftOutcome o : outcomes) {
                switch (o.coverage()) {
                    case EngineResult.FULL -> full++;
                    case EngineResult.BELOW_TARGET -> below++;
                    default -> under++;
                }
            }
            Summary summary = new Summary(outcomes.size(), full, below, under, planned.size(), unfilled,
                    Map.copyOf(rejectionsByCode));
            return new EngineResult(summary, outcomes, List.copyOf(planned), List.copyOf(missing.values()), loads(),
                    List.copyOf(warnings));
        }

        private ShiftOutcome planShift(ShiftInfo shift) {
            List<String> existing = ctx.activeAssignmentsOfShift(shift.id()).stream()
                    .map(Assignment::userId).distinct().toList();
            int need = Math.max(0, shift.totalRequired() - existing.size());
            Set<String> used = new HashSet<>(existing);
            Map<String, Rejection> rejections = new LinkedHashMap<>();
            List<Decision> decisions = new ArrayList<>();
            List<String> shiftWarnings = new ArrayList<>();

            for (Slot slot : buildSlots(shift, existing, need)) {
                Slot target = slot;
                if (slot.userId() != null) {
                    if (used.contains(slot.userId())) {
                        continue;
                    }
                    ValidationResult named = validator.validate(ctx, new AssignmentCandidate(slot.userId(), shift.id(), null));
                    if (named.valid()) {
                        String reason = MANDATORY.equals(slot.type())
                                ? "Nhân viên bắt buộc của ca."
                                : "Nhân viên được chỉ định ưu tiên cho ca.";
                        commit(shift, slot, ctx.employee(slot.userId()), 0, List.of(reason), 1, used, decisions);
                        continue;
                    }
                    reject(rejections, slot.userId(), named);
                    shiftWarnings.add((MANDATORY.equals(slot.type()) ? "Nhân viên bắt buộc " : "Nhân viên ưu tiên ")
                            + label(slot.userId()) + " không đủ điều kiện; tìm người hợp lệ khác cho vị trí này.");
                    target = Slot.flex();
                }

                List<Scored> candidates = eligibleFor(shift, target, used, rejections);
                if (candidates.isEmpty()) {
                    unfilled++;
                    shiftWarnings.add("Không có ứng viên hợp lệ cho vị trí " + target.label()
                            + "; giữ thiếu người, không bù bằng cách vượt trần hay vi phạm nghỉ.");
                    continue;
                }
                Scored best = candidates.get(0);
                // Kiểm tra lại ngay trước khi chốt, với đúng ngữ cảnh hiện tại.
                ValidationResult recheck = validator.validate(ctx,
                        new AssignmentCandidate(best.employee().id(), shift.id(), target.levelId()));
                if (!recheck.valid()) {
                    reject(rejections, best.employee().id(), recheck);
                    unfilled++;
                    continue;
                }
                commit(shift, target, best.employee(), best.score(), best.reasons(), candidates.size(), used, decisions);
            }

            // Người được chọn ở vị trí khác của cùng ca không còn là "bị loại".
            used.forEach(rejections::remove);
            int plannedHere = decisions.size();
            int filled = existing.size() + plannedHere;
            String coverage = filled >= shift.totalRequired() ? EngineResult.FULL
                    : shift.understaffedWith(filled) ? EngineResult.UNDERSTAFFED : EngineResult.BELOW_TARGET;
            if (!shiftWarnings.isEmpty()) {
                shiftWarnings.forEach(w -> warnings.add("Ca \"" + shift.name() + "\" " + shift.date() + ": " + w));
            }
            return new ShiftOutcome(shift.id(), shift.name(), shift.date(), shift.startTime(), shift.endTime(),
                    shift.totalRequired(), shift.minimum(), existing.size(), plannedHere, filled, coverage,
                    List.copyOf(decisions), List.copyOf(rejections.values()), List.copyOf(shiftWarnings));
        }

        /**
         * Vị trí cần điền: người bắt buộc, vị trí theo Level (Level cao trước), người ưu tiên rồi vị trí tự do. Người đã
         * được phân sẵn chiếm trước vị trí phù hợp với họ; tổng số vị trí không vượt nhu cầu còn lại.
         */
        List<Slot> buildSlots(ShiftInfo shift, List<String> existing, int need) {
            List<String> remaining = new ArrayList<>(existing);
            List<Slot> slots = new ArrayList<>();
            for (String userId : sorted(shift.mandatoryUserIds())) {
                if (!remaining.remove(userId)) {
                    slots.add(new Slot(MANDATORY, userId, null));
                }
            }
            List<Map.Entry<String, Integer>> levels = new ArrayList<>(shift.levelRequirements().entrySet());
            Map<String, Integer> levelOrder = ctx.rules().levelOrder();
            levels.sort(Comparator.comparing((Map.Entry<String, Integer> e) -> -levelOrder.getOrDefault(e.getKey(), 0))
                    .thenComparing(Map.Entry::getKey));
            for (Map.Entry<String, Integer> requirement : levels) {
                int count = requirement.getValue();
                for (Iterator<String> it = remaining.iterator(); it.hasNext() && count > 0; ) {
                    Employee e = ctx.employee(it.next());
                    if (e != null && ctx.rules().levelSatisfies(e.levelId(), requirement.getKey())) {
                        it.remove();
                        count--;
                    }
                }
                for (int i = 0; i < count; i++) {
                    slots.add(new Slot(LEVEL, null, requirement.getKey()));
                }
            }
            if (!"level".equals(shift.mode())) {
                for (String userId : sorted(shift.preferredUserIds())) {
                    if (!remaining.remove(userId)) {
                        slots.add(new Slot(PREFERRED, userId, null));
                    }
                }
            }
            while (slots.size() < need) {
                slots.add(Slot.flex());
            }
            return slots.size() > need ? new ArrayList<>(slots.subList(0, need)) : slots;
        }

        /** Ứng viên qua mọi ràng buộc cứng, xếp theo điểm mềm giảm dần. */
        private List<Scored> eligibleFor(ShiftInfo shift, Slot slot, Set<String> used, Map<String, Rejection> rejections) {
            List<Scored> result = new ArrayList<>();
            for (Employee e : ctx.employees()) {
                if (!e.active() || used.contains(e.id())) {
                    continue;
                }
                ValidationResult r = validator.validate(ctx, new AssignmentCandidate(e.id(), shift.id(), slot.levelId()));
                if (!r.valid()) {
                    reject(rejections, e.id(), r);
                    continue;
                }
                result.add(score(e, shift, slot));
            }
            result.sort(Comparator.comparingDouble(Scored::score).reversed()
                    .thenComparingDouble(s -> plannedHours.getOrDefault(s.employee().id(), 0.0))
                    .thenComparing(s -> s.employee().id()));
            return result;
        }

        /** Điểm tiêu chí mềm và diễn giải; không ảnh hưởng tới việc ứng viên có hợp lệ hay không. */
        Scored score(Employee e, ShiftInfo shift, Slot slot) {
            List<String> reasons = new ArrayList<>();
            double score = 0;
            if (shift.preferredUserIds().contains(e.id())) {
                score += PREFERRED_BONUS;
                reasons.add("Nằm trong danh sách ưu tiên của ca (+" + fmt(PREFERRED_BONUS) + ").");
            }
            if (slot.levelId() != null) {
                if (slot.levelId().equals(e.levelId())) {
                    score += EXACT_LEVEL_BONUS;
                    reasons.add("Đúng Level " + slot.levelId() + " của vị trí (+" + fmt(EXACT_LEVEL_BONUS) + ").");
                } else {
                    reasons.add("Level " + e.levelId() + " được phép thay vị trí Level " + slot.levelId() + " (+0).");
                }
            }

            EmployeeRules rules = ctx.rulesFor(e, shift.date());
            double hours = savedHours.getOrDefault(e.id(), 0.0) + plannedHours.getOrDefault(e.id(), 0.0);
            int shiftsSoFar = savedShifts.getOrDefault(e.id(), 0) + plannedShifts.getOrDefault(e.id(), 0);
            Integer wHours = rules.weightBalanceHours();
            Integer wShifts = rules.weightBalanceShifts();
            if (wHours == null && wShifts == null) {
                wHours = 100;
            }
            if (wHours != null && wHours > 0) {
                double weight = wHours / 100.0;
                if (rules.targetHours() != null) {
                    double deficit = rules.targetHours() - hours;
                    double points = weight * deficit;
                    score += points;
                    reasons.add((deficit > 0
                            ? "Còn thiếu " + fmt(deficit) + " giờ so với mục tiêu " + fmt(rules.targetHours()) + " giờ"
                            : "Đã đạt " + fmt(hours) + "/" + fmt(rules.targetHours()) + " giờ mục tiêu")
                            + " (" + signed(points) + ").");
                } else {
                    double points = -weight * hours;
                    score += points;
                    reasons.add("Chưa có giờ mục tiêu; ưu tiên người ít giờ hơn, đang có " + fmt(hours) + " giờ ("
                            + signed(points) + ").");
                }
            }
            if (wShifts != null && wShifts > 0) {
                double points = -(wShifts / 100.0) * POINTS_PER_SHIFT * shiftsSoFar;
                score += points;
                reasons.add("Chia đều số ca: đang có " + shiftsSoFar + " ca trong kỳ (" + signed(points) + ").");
            }
            return new Scored(e, score, List.copyOf(reasons));
        }

        private void commit(ShiftInfo shift, Slot slot, Employee employee, double score, List<String> reasons,
                            int eligibleCount, Set<String> used, List<Decision> decisions) {
            ctx.addProposed(new Assignment("planned_" + (++sequence), shift.id(), employee.id(), Assignment.CONFIRMED));
            used.add(employee.id());
            planned.add(new PlannedAssignment(shift.id(), employee.id(), slot.type(), slot.levelId()));
            double h = hoursInPeriod(shift);
            plannedHours.merge(employee.id(), h, Double::sum);
            if (h > 0) {
                plannedShifts.merge(employee.id(), 1, Integer::sum);
            }
            decisions.add(new Decision(employee.id(), employee.label(), slot.type(), slot.levelId(), round(score),
                    reasons, eligibleCount));
        }

        private void reject(Map<String, Rejection> rejections, String userId, ValidationResult result) {
            if (rejections.containsKey(userId)) {
                return;
            }
            rejections.put(userId, new Rejection(userId, label(userId), result.violations()));
            for (Violation v : result.violations()) {
                rejectionsByCode.merge(v.code().name(), 1L, Long::sum);
                if (v.code() == ViolationCode.MISSING_PARAMETER) {
                    missing.putIfAbsent(userId + "|" + v.parameter(),
                            new MissingParameterReport(userId, label(userId), v.parameter(), v.message()));
                }
            }
        }

        private List<EmployeeLoad> loads() {
            List<EmployeeLoad> result = new ArrayList<>();
            for (Employee e : ctx.employees()) {
                if (!e.active() && !savedHours.containsKey(e.id()) && !plannedHours.containsKey(e.id())) {
                    continue;
                }
                Double target = referenceDate == null ? null : ctx.rulesFor(e, referenceDate).targetHours();
                result.add(new EmployeeLoad(e.id(), e.label(), target == null ? null : round(target),
                        round(savedHours.getOrDefault(e.id(), 0.0)), round(plannedHours.getOrDefault(e.id(), 0.0)),
                        plannedShifts.getOrDefault(e.id(), 0)));
            }
            return result;
        }

        /** Giờ làm của ca (đã trừ giờ nghỉ) rơi vào kỳ; cả ca khi không có kỳ. */
        private double hoursInPeriod(ShiftInfo shift) {
            return WorkTime.workMinutesByDay(shift).entrySet().stream()
                    .filter(entry -> ctx.period() == null || ctx.period().contains(entry.getKey()))
                    .mapToDouble(Map.Entry::getValue)
                    .sum() / 60.0;
        }

        private String label(String userId) {
            Employee e = ctx.employee(userId);
            return e == null ? userId : e.label();
        }
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }

    private static String signed(double value) {
        return (value >= 0 ? "+" : "") + fmt(value);
    }
}
