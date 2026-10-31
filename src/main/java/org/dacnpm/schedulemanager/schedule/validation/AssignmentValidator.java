package org.dacnpm.schedulemanager.schedule.validation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.model.TimeRange;
import org.dacnpm.schedulemanager.schedule.model.UserAvailability;
import org.dacnpm.schedulemanager.schedule.rules.EmployeeRules;
import org.springframework.stereotype.Component;

/**
 * Dịch vụ kiểm tra phân công dùng chung cho phân lịch tự động, phân công thủ công, công bố,
 * đổi ca, nhận ca và nghỉ phép tác động lịch. Chỉ đọc {@link ScheduleContext}, không ghi dữ liệu.
 *
 * <p>Mọi kiểm tra ở đây là ràng buộc cứng; tiêu chí mềm (ưu tiên, cân bằng) nằm ở engine.
 */
@Component
public class AssignmentValidator {

    private static final double EPSILON = 1e-6;

    public ValidationResult validate(ScheduleContext ctx, AssignmentCandidate candidate) {
        List<Violation> out = new ArrayList<>();
        ShiftInfo shift = ctx.shift(candidate.shiftId());
        if (shift == null) {
            throw new IllegalArgumentException("Không tìm thấy ca " + candidate.shiftId() + ".");
        }
        Employee employee = ctx.employee(candidate.userId());
        if (employee == null) {
            out.add(violation(ViolationCode.UNKNOWN_EMPLOYEE, candidate.userId(), shift, null, null, null,
                    "Không tìm thấy nhân viên " + candidate.userId() + "."));
            return new ValidationResult(out);
        }
        if (!employee.active()) {
            out.add(violation(ViolationCode.INACTIVE, employee, shift, "status", null, null,
                    employee.label() + " không ở trạng thái active."));
            return new ValidationResult(out);
        }

        // Quy tắc hiệu lực theo ngày bắt đầu ca. Thiếu tham số bắt buộc thì báo đúng nhân viên và từng tham số,
        // không tự điền giá trị.
        EmployeeRules rules = ctx.rulesFor(employee, shift.date());
        if (!rules.ready()) {
            for (EmployeeRules.MissingParameter m : rules.missing()) {
                out.add(violation(ViolationCode.MISSING_PARAMETER, employee, shift, m.key(), null, null,
                        employee.label() + " thiếu thông số " + m.label() + " (" + m.key() + "): " + m.reason()));
            }
            return new ValidationResult(out);
        }

        List<Assignment> userAssignments = ctx.activeAssignmentsOfUser(employee.id());
        checkDuplicateAndCapacity(ctx, employee, shift, out);
        checkLevel(ctx, employee, shift, candidate.requiredLevelId(), out);
        checkPosition(employee, shift, out);
        checkLocation(employee, shift, out);
        checkSkillsAndCertificates(employee, shift, out);
        checkAvailability(ctx, employee, shift, out);
        checkOverlapAndRest(ctx, employee, shift, userAssignments, rules, out);
        checkHours(ctx, employee, shift, userAssignments, rules, out);
        checkConsecutiveDays(ctx, employee, shift, userAssignments, rules, out);
        return new ValidationResult(out);
    }

    private void checkDuplicateAndCapacity(ScheduleContext ctx, Employee employee, ShiftInfo shift, List<Violation> out) {
        List<Assignment> onShift = ctx.activeAssignmentsOfShift(shift.id());
        if (onShift.stream().anyMatch(a -> a.userId().equals(employee.id()))) {
            out.add(violation(ViolationCode.ALREADY_ASSIGNED, employee, shift, null, null, null,
                    employee.label() + " đã được phân vào ca \"" + shift.name() + "\"."));
        } else if (onShift.size() >= shift.capacity()) {
            out.add(violation(ViolationCode.SHIFT_FULL, employee, shift, "max_allowed",
                    (double) onShift.size(), (double) shift.capacity(),
                    "Ca \"" + shift.name() + "\" đã đủ " + shift.capacity() + " người."));
        }
    }

    private void checkLevel(ScheduleContext ctx, Employee employee, ShiftInfo shift, String requiredLevelId, List<Violation> out) {
        ScheduleRules rules = ctx.rules();
        boolean named = shift.mandatoryUserIds().contains(employee.id()) || shift.preferredUserIds().contains(employee.id());
        List<String> required = new ArrayList<>();
        if (requiredLevelId != null) {
            required.add(requiredLevelId);
        } else if ("level".equals(shift.mode()) && !named) {
            required.addAll(shift.levelIds());
        }
        if (required.isEmpty()) {
            return;
        }
        if (employee.levelId() == null) {
            out.add(violation(ViolationCode.MISSING_PARAMETER, employee, shift, "level_id", null, null,
                    employee.label() + " thiếu thông số level_id."));
            return;
        }
        boolean ok = required.stream().anyMatch(level -> rules.levelSatisfies(employee.levelId(), level));
        if (!ok) {
            out.add(violation(ViolationCode.LEVEL, employee, shift, "level_id", null, null,
                    "Level " + employee.levelId() + " của " + employee.label()
                            + " không đáp ứng vị trí " + String.join(", ", required)
                            + " (chỉ Level cao hơn được thay Level thấp)."));
        }
    }

    private void checkPosition(Employee employee, ShiftInfo shift, List<Violation> out) {
        if (shift.requiredPositionIds().isEmpty()) {
            return;
        }
        boolean holdsRequired = employee.positionIds().stream().anyMatch(shift.requiredPositionIds()::contains);
        if (!holdsRequired) {
            out.add(violation(ViolationCode.POSITION, employee, shift, "position_id", null, null,
                    employee.label() + " không giữ chức vụ nào trong: "
                            + String.join(", ", new TreeSet<>(shift.requiredPositionIds())) + "."));
        }
    }

    private void checkLocation(Employee employee, ShiftInfo shift, List<Violation> out) {
        // Nhân viên chưa được gán địa điểm nào được coi là không giới hạn địa điểm.
        if (shift.locationId() != null && !employee.locationIds().isEmpty()
                && !employee.locationIds().contains(shift.locationId())) {
            out.add(violation(ViolationCode.LOCATION, employee, shift, "location_id", null, null,
                    employee.label() + " không được gán địa điểm " + shift.locationId() + "."));
        }
    }

    private void checkSkillsAndCertificates(Employee employee, ShiftInfo shift, List<Violation> out) {
        for (String skill : new TreeSet<>(shift.requiredSkills())) {
            if (!employee.skills().contains(skill)) {
                out.add(violation(ViolationCode.SKILL, employee, shift, "skill:" + skill, null, null,
                        employee.label() + " thiếu kỹ năng " + skill + "."));
            }
        }
        LocalDate lastDay = shift.endAt().toLocalDate();
        for (String certificate : new TreeSet<>(shift.requiredCertificates())) {
            if (!employee.certificateValidUntil().containsKey(certificate)) {
                out.add(violation(ViolationCode.CERTIFICATE, employee, shift, "certificate:" + certificate, null, null,
                        employee.label() + " chưa có chứng chỉ " + certificate + "."));
            } else {
                LocalDate validUntil = employee.certificateValidUntil().get(certificate);
                if (validUntil != null && validUntil.isBefore(lastDay)) {
                    out.add(violation(ViolationCode.CERTIFICATE, employee, shift, "certificate:" + certificate, null, null,
                            "Chứng chỉ " + certificate + " của " + employee.label() + " hết hạn " + validUntil + "."));
                }
            }
        }
    }

    private void checkAvailability(ScheduleContext ctx, Employee employee, ShiftInfo shift, List<Violation> out) {
        for (LocalDate day : WorkTime.workMinutesByDay(shift).keySet()) {
            if (ctx.blocked(employee.id(), day)) {
                out.add(violation(ViolationCode.UNAVAILABLE, employee, shift, "blocked_date", null, null,
                        employee.label() + " không thể làm việc ngày " + day + "."));
                return;
            }
        }
        UserAvailability availability = ctx.availability(employee.id());
        if(availability!=null)for(var entry:availability.unavailableByDate().entrySet()) {
            for(TimeRange range:entry.getValue()) {
                var start=entry.getKey().atStartOfDay().plusMinutes(range.startMinute());
                var end=entry.getKey().atStartOfDay().plusMinutes(range.endMinute());
                if(start.isBefore(shift.endAt()) && shift.startAt().isBefore(end)) {
                    out.add(violation(ViolationCode.UNAVAILABLE_INTERVAL,employee,shift,"unavailable",null,null,
                        employee.label()+" không thể làm trong khoảng đã đăng ký không thể làm hoặc nghỉ đã duyệt."));
                    return;
                }
            }
        }
        if (availability == null || !availability.submitted()) {
            out.add(violation(ViolationCode.NOT_AVAILABLE_TIME, employee, shift, "availability", null, null,
                    employee.label() + " chưa gửi đăng ký thời gian rảnh cho kỳ này."));
            return;
        }
        if (!coversShift(availability, shift)) {
            out.add(violation(ViolationCode.NOT_AVAILABLE_TIME, employee, shift, "availability", null, null,
                    "Thời gian rảnh của " + employee.label() + " không bao phủ ca " + shift.startTime()
                            + "-" + shift.endTime() + " ngày " + shift.date() + "."));
        }
    }

    /**
     * Thời gian rảnh có phủ trọn ca không. Khung rảnh của ngày trước, ngày của ca và ngày sau được đặt chung một trục
     * phút (0 là 00:00 ngày của ca) rồi nối các khung chạm nhau, nên ca qua đêm được phủ bởi khung tối hôm trước và
     * khung sáng hôm sau, và hai khung liền nhau trong ngày được tính là một. Kết thúc lúc 23:59 được hiểu là hết ngày
     * (nút "Cả ngày" của giao diện lưu 00:00-23:59).
     */
    static boolean coversShift(UserAvailability availability, ShiftInfo shift) {
        int start = shift.startTime().getHour() * 60 + shift.startTime().getMinute();
        int end = start + (int) java.time.Duration.between(shift.startAt(), shift.endAt()).toMinutes();

        List<int[]> ranges = new ArrayList<>();
        for (int offset = -1; offset <= 1; offset++) {
            int shiftMinutes = offset * 1440;
            for (TimeRange slot : availability.slotsByDate().getOrDefault(shift.date().plusDays(offset), List.of())) {
                int slotEnd = slot.endMinute() % 1440 == 1439 ? slot.endMinute() + 1 : slot.endMinute();
                ranges.add(new int[] {slot.startMinute() + shiftMinutes, slotEnd + shiftMinutes});
            }
        }
        ranges.sort(java.util.Comparator.comparingInt(r -> r[0]));

        int coveredFrom = Integer.MIN_VALUE;
        int coveredTo = Integer.MIN_VALUE;
        for (int[] range : ranges) {
            if (range[0] > coveredTo) {
                coveredFrom = range[0];
            }
            coveredTo = Math.max(coveredTo, range[1]);
            if (coveredFrom <= start && coveredTo >= end) {
                return true;
            }
        }
        return false;
    }

    private void checkOverlapAndRest(ScheduleContext ctx, Employee employee, ShiftInfo shift,
                                     List<Assignment> userAssignments, EmployeeRules rules, List<Violation> out) {
        double minRestMinutes = rules.minRestHours() * 60;
        boolean overlapReported = false;
        boolean restReported = false;
        for (Assignment other : userAssignments) {
            if (other.shiftId().equals(shift.id())) {
                continue;
            }
            ShiftInfo otherShift = ctx.shiftOf(other);
            long rest = WorkTime.restMinutesBetween(shift, otherShift);
            if (rest < 0) {
                if (!overlapReported) {
                    out.add(violation(ViolationCode.OVERLAP, employee, shift, null, null, null,
                            "Ca \"" + shift.name() + "\" (" + shift.date() + " " + shift.startTime() + "-" + shift.endTime()
                                    + ") trùng giờ với ca \"" + otherShift.name() + "\" (" + otherShift.date() + " "
                                    + otherShift.startTime() + "-" + otherShift.endTime() + ")."));
                    overlapReported = true;
                }
            } else if (rest < minRestMinutes - EPSILON && !restReported) {
                out.add(violation(ViolationCode.MIN_REST, employee, shift, "min_rest_between_shifts",
                        rest / 60.0, rules.minRestHours(),
                        "Nghỉ giữa ca chỉ " + hours(rest / 60.0) + "h, cần tối thiểu "
                                + hours(rules.minRestHours()) + "h (so với ca \""
                                + otherShift.name() + "\" ngày " + otherShift.date() + ")."));
                restReported = true;
            }
        }
    }

    private void checkHours(ScheduleContext ctx, Employee employee, ShiftInfo shift,
                            List<Assignment> userAssignments, EmployeeRules rules, List<Violation> out) {
        Map<LocalDate, Double> load = new HashMap<>();
        for (Assignment other : userAssignments) {
            if (!other.shiftId().equals(shift.id())) {
                WorkTime.workMinutesByDay(ctx.shiftOf(other)).forEach((d, m) -> load.merge(d, m, Double::sum));
            }
        }
        Map<LocalDate, Double> added = WorkTime.workMinutesByDay(shift);

        for (Map.Entry<LocalDate, Double> e : added.entrySet()) {
            double total = load.getOrDefault(e.getKey(), 0.0) + e.getValue();
            if (total > rules.maxHoursPerDay() * 60 + EPSILON) {
                out.add(violation(ViolationCode.HOURS_PER_DAY, employee, shift, "max_hours_per_day",
                        total / 60, rules.maxHoursPerDay(),
                        "Vượt giới hạn " + hours(rules.maxHoursPerDay()) + "h/ngày ngày " + e.getKey()
                                + ": sẽ là " + hours(total / 60) + "h."));
                break;
            }
        }

        double weekLimit = rules.maxHoursPerWeek();
        Set<LocalDate> weeks = new TreeSet<>();
        added.keySet().forEach(d -> weeks.add(WorkTime.weekStart(d)));
        for (LocalDate week : weeks) {
            double total = sumDays(load, week, week.plusDays(6)) + sumDays(added, week, week.plusDays(6));
            if (total > weekLimit * 60 + EPSILON) {
                out.add(violation(ViolationCode.HOURS_PER_WEEK, employee, shift, "weekly_limit",
                        total / 60, weekLimit,
                        "Vượt trần " + hours(weekLimit) + "h/tuần (tuần từ " + week + "): sẽ là "
                                + hours(total / 60) + "h"
                                + (rules.maxHoursFormula() == null ? "." : "; trần = " + rules.maxHoursFormula() + ".")));
                break;
            }
        }

        if (rules.maxHoursPerPeriod() != null && ctx.period() != null
                && added.keySet().stream().anyMatch(ctx.period()::contains)) {
            double total = sumDays(load, ctx.period().startDate(), ctx.period().endDate())
                    + sumDays(added, ctx.period().startDate(), ctx.period().endDate());
            if (total > rules.maxHoursPerPeriod() * 60 + EPSILON) {
                out.add(violation(ViolationCode.HOURS_PER_PERIOD, employee, shift, "max_hours_per_period",
                        total / 60, rules.maxHoursPerPeriod(),
                        "Vượt giới hạn " + hours(rules.maxHoursPerPeriod()) + "h/kỳ: sẽ là " + hours(total / 60) + "h."));
            }
        }
    }

    /**
     * Trần giờ mỗi tuần = giá trị nhỏ nhất của: giới hạn chính sách, giới hạn hợp đồng và
     * giờ mục tiêu tuần × target_rate / 100 (ví dụ 60 × 125% = 75).
     */
    public static double weeklyLimit(ScheduleRules rules, Employee employee) {
        return EmployeeRules.derive(rules, employee, null).maxHoursPerWeek();
    }

    private void checkConsecutiveDays(ScheduleContext ctx, Employee employee, ShiftInfo shift,
                                      List<Assignment> userAssignments, EmployeeRules rules, List<Violation> out) {
        Integer max = rules.maxConsecutiveDays();
        if (max == null) {
            return;
        }
        Set<LocalDate> worked = new HashSet<>(WorkTime.workMinutesByDay(shift).keySet());
        Set<LocalDate> candidateDays = new HashSet<>(worked);
        for (Assignment other : userAssignments) {
            if (!other.shiftId().equals(shift.id())) {
                worked.addAll(WorkTime.workMinutesByDay(ctx.shiftOf(other)).keySet());
            }
        }
        for (LocalDate day : candidateDays) {
            int run = 1;
            for (LocalDate d = day.minusDays(1); worked.contains(d); d = d.minusDays(1)) {
                run++;
            }
            for (LocalDate d = day.plusDays(1); worked.contains(d); d = d.plusDays(1)) {
                run++;
            }
            if (run > max) {
                out.add(violation(ViolationCode.CONSECUTIVE_DAYS, employee, shift, "max_consecutive_days",
                        (double) run, (double) max,
                        "Làm liên tiếp " + run + " ngày, vượt giới hạn " + max + " ngày."));
                return;
            }
        }
    }

    private static double sumDays(Map<LocalDate, Double> minutes, LocalDate from, LocalDate to) {
        return minutes.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(from) && !e.getKey().isAfter(to))
                .mapToDouble(Map.Entry::getValue)
                .sum();
    }

    private static String hours(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }

    private static Violation violation(ViolationCode code, Employee employee, ShiftInfo shift, String parameter,
                                       Double actual, Double limit, String message) {
        return new Violation(code, employee.id(), shift.id(), parameter, actual, limit, message);
    }

    private static Violation violation(ViolationCode code, String userId, ShiftInfo shift, String parameter,
                                       Double actual, Double limit, String message) {
        return new Violation(code, userId, shift.id(), parameter, actual, limit, message);
    }
}
