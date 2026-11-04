package org.dacnpm.schedulemanager.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.dacnpm.schedulemanager.schedule.model.ScheduleContext;
import org.dacnpm.schedulemanager.schedule.model.ScheduleRules;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;
import org.dacnpm.schedulemanager.schedule.model.TimeRange;
import org.dacnpm.schedulemanager.schedule.model.UserAvailability;

/** Dữ liệu mẫu dùng chung cho các test phân lịch. day(0) là thứ hai. */
public final class ScheduleFixtures {

    private static final LocalDate MONDAY = LocalDate.of(2026, 1, 5);

    /** Kỳ 4 tuần: day(0) đến day(27). */
    public static final Period PERIOD = new Period("p1", "Kỳ test", day(0), day(27));

    private ScheduleFixtures() {
    }

    public static LocalDate day(int offset) {
        return MONDAY.plusDays(offset);
    }

    public static LocalTime t(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    /**
     * Quy tắc mẫu: tối đa 75h/tuần (như V7), 200h/kỳ, nghỉ giữa ca 11h, tối đa 6 ngày liên tiếp;
     * Level cao hơn được thay Level thấp hơn.
     */
    public static ScheduleRules rules(int maxHoursPerDay, double targetHoursPerWeek) {
        return new ScheduleRules(maxHoursPerDay, 75, 200, 11, targetHoursPerWeek, 6,
                Map.of("lvl3", Set.of("lvl2", "lvl1"), "lvl2", Set.of("lvl1")));
    }

    public static Employee employee(String id, String levelId, double targetRate) {
        return new Employee(id, "Nhân viên " + id, true, levelId, targetRate, null, null, null, null);
    }

    public static ShiftInfo shift(String id, LocalDate date, LocalTime start, LocalTime end, int breakMinutes,
                                  int totalRequired, String mode) {
        Map<String, Integer> levels = "user".equals(mode) ? null : Map.of("lvl1", totalRequired);
        return new ShiftInfo(id, "Ca " + id, date, start, end, breakMinutes, mode, totalRequired, null, levels,
                null, null, null, null, null);
    }

    public static Assignment saved(String id, String shiftId, String userId) {
        return new Assignment(id, shiftId, userId, Assignment.CONFIRMED);
    }

    /** Sáu ca 12 giờ từ thứ hai đến thứ bảy của tuần đầu (base0..base5), tổng 72 giờ. */
    public static List<ShiftInfo> baseWeekShifts() {
        List<ShiftInfo> result = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            result.add(shift("base" + i, day(i), t(7, 0), t(19, 0), 0, 1, "user"));
        }
        return result;
    }

    /** Mọi nhân viên đã gửi đăng ký rảnh suốt kỳ (kể cả ca qua đêm). */
    public static Map<String, UserAvailability> freeAllPeriod(List<Employee> staff) {
        Map<LocalDate, List<TimeRange>> slots = new HashMap<>();
        for (LocalDate d = PERIOD.startDate().minusDays(1); !d.isAfter(PERIOD.endDate()); d = d.plusDays(1)) {
            slots.put(d, List.of(new TimeRange(0, 2880)));
        }
        Map<String, UserAvailability> result = new HashMap<>();
        staff.forEach(e -> result.put(e.id(), new UserAvailability(e.id(), true, slots)));
        return result;
    }

    public static ScheduleContext context(ScheduleRules rules, List<Employee> staff, List<ShiftInfo> shifts,
                                          List<Assignment> saved) {
        return new ScheduleContext(PERIOD, rules, staff, shifts, freeAllPeriod(staff), Map.of(), saved, Set.of());
    }
}
