package org.dacnpm.schedulemanager.schedule.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ảnh chụp dữ liệu một kỳ dùng cho kiểm tra phân công. Chỉ đọc, trừ danh sách phân công đề xuất
 * ({@link #addProposed}) dùng khi kiểm tra nhiều thay đổi liên tiếp (ví dụ đổi ca).
 *
 * <p>Phân công có id thuộc replacedAssignmentIds bị bỏ khỏi ngữ cảnh: chúng đang được thay thế
 * nên không được tính trùng.
 */
public class ScheduleContext {

    private final Period period;
    private final ScheduleRules rules;
    private final Map<String, Employee> employees = new LinkedHashMap<>();
    private final Map<String, ShiftInfo> shifts = new LinkedHashMap<>();
    private final Map<String, UserAvailability> availability;
    private final Map<String, Set<LocalDate>> blockedDates;
    private final List<Assignment> assignments = new ArrayList<>();

    public ScheduleContext(Period period, ScheduleRules rules, List<Employee> employees, List<ShiftInfo> shifts,
                           Map<String, UserAvailability> availability, Map<String, Set<LocalDate>> blockedDates,
                           List<Assignment> savedAssignments, Set<String> replacedAssignmentIds) {
        if (rules == null) {
            throw new IllegalArgumentException("Thiếu quy tắc phân lịch.");
        }
        this.period = period;
        this.rules = rules;
        if (employees != null) {
            employees.forEach(e -> this.employees.put(e.id(), e));
        }
        if (shifts != null) {
            shifts.forEach(s -> this.shifts.put(s.id(), s));
        }
        this.availability = availability == null ? Map.of() : new HashMap<>(availability);
        this.blockedDates = blockedDates == null ? Map.of() : new HashMap<>(blockedDates);
        Set<String> replaced = replacedAssignmentIds == null ? Set.of() : replacedAssignmentIds;
        if (savedAssignments != null) {
            savedAssignments.stream().filter(a -> !replaced.contains(a.id())).forEach(this.assignments::add);
        }
    }

    public Period period() {
        return period;
    }

    public ScheduleRules rules() {
        return rules;
    }

    public List<Employee> employees() {
        return List.copyOf(employees.values());
    }

    public List<ShiftInfo> shifts() {
        return List.copyOf(shifts.values());
    }

    public Employee employee(String userId) {
        return employees.get(userId);
    }

    public ShiftInfo shift(String shiftId) {
        return shifts.get(shiftId);
    }

    public ShiftInfo shiftOf(Assignment assignment) {
        return shifts.get(assignment.shiftId());
    }

    /** null nếu nhân viên chưa có đăng ký thời gian rảnh cho kỳ. */
    public UserAvailability availability(String userId) {
        return availability.get(userId);
    }

    public boolean blocked(String userId, LocalDate day) {
        return blockedDates.getOrDefault(userId, Set.of()).contains(day);
    }

    /** Phân công đang hiệu lực của nhân viên, chỉ gồm các ca có trong ngữ cảnh. */
    public List<Assignment> activeAssignmentsOfUser(String userId) {
        return assignments.stream()
                .filter(a -> a.active() && a.userId().equals(userId) && shifts.containsKey(a.shiftId()))
                .toList();
    }

    public List<Assignment> activeAssignmentsOfShift(String shiftId) {
        return assignments.stream()
                .filter(a -> a.active() && a.shiftId().equals(shiftId))
                .toList();
    }

    /** Thêm một phân công chưa lưu để các lần kiểm tra sau tính đến nó. */
    public void addProposed(Assignment assignment) {
        assignments.add(assignment);
    }
}
