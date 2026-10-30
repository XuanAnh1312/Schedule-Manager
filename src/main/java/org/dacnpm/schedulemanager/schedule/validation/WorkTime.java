package org.dacnpm.schedulemanager.schedule.validation;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.TreeMap;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;

/** Tính thời gian làm việc của ca: tách theo ngày (ca qua đêm) và trừ giờ nghỉ. */
final class WorkTime {

    private WorkTime() {
    }

    /**
     * Phút làm việc của ca theo từng ngày lịch. Giờ nghỉ được trừ theo tỷ lệ thời lượng của mỗi phần ca,
     * nên ca qua đêm 22:00-06:00 nghỉ 60 phút tính 105 phút cho ngày đầu và 315 phút cho ngày sau.
     */
    static Map<LocalDate, Double> workMinutesByDay(ShiftInfo shift) {
        LocalDateTime start = shift.startAt();
        LocalDateTime end = shift.endAt();
        double total = Duration.between(start, end).toMinutes();
        double ratio = (total - shift.breakMinutes()) / total;
        Map<LocalDate, Double> result = new TreeMap<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(end)) {
            LocalDateTime nextMidnight = cursor.toLocalDate().plusDays(1).atStartOfDay();
            LocalDateTime segmentEnd = nextMidnight.isBefore(end) ? nextMidnight : end;
            long minutes = Duration.between(cursor, segmentEnd).toMinutes();
            if (minutes > 0) {
                result.merge(cursor.toLocalDate(), minutes * ratio, Double::sum);
            }
            cursor = segmentEnd;
        }
        return result;
    }

    /** Số phút nghỉ giữa hai ca; âm nếu hai ca trùng giờ. Hai ca nối tiếp nhau có thời gian nghỉ 0. */
    static long restMinutesBetween(ShiftInfo a, ShiftInfo b) {
        if (a.startAt().isBefore(b.endAt()) && b.startAt().isBefore(a.endAt())) {
            return -1;
        }
        return a.startAt().isBefore(b.startAt())
                ? Duration.between(a.endAt(), b.startAt()).toMinutes()
                : Duration.between(b.endAt(), a.startAt()).toMinutes();
    }

    /** Thứ hai của tuần chứa ngày này. */
    static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
