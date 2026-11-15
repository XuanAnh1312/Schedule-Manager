package org.dacnpm.schedulemanager.api.comms;

import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.events.EventTypes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Nhắc nhân viên chưa chấm công vào ca (BAO-05). Ca đã công bố, phân công confirmed, đã bắt đầu quá
 * {@code app.reminders.missing-checkin-minutes} phút (mặc định 15) trong vòng 12 giờ gần nhất, chưa có bản ghi chấm công.
 * eventId theo phân công → mỗi ca chỉ nhắc một lần. Giờ ca so theo giờ nghiệp vụ (Asia/Ho_Chi_Minh) của {@link Clock}.
 * Đặt 0 để tắt nhắc.
 */
@Profile("sqlserver")
@Component
public class AttendanceReminderJob {

    private final JdbcTemplate jdbc;
    private final DomainEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int minutes;

    public AttendanceReminderJob(JdbcTemplate jdbc, DomainEventPublisher events, TransactionTemplate tx, Clock clock,
                                 @Value("${app.reminders.missing-checkin-minutes:15}") int minutes) {
        this.jdbc = jdbc;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.minutes = minutes;
    }

    @Scheduled(cron = "${app.reminders.missing-checkin-cron:0 */10 * * * *}")
    public void run() {
        if (minutes <= 0) return;
        LocalDateTime now = LocalDateTime.now(clock);
        for (Map<String, Object> row : due(now)) {
            String assignmentId = (String) row.get("assignment_id");
            tx.executeWithoutResult(s -> events.publish(new DomainEvent(
                    DomainEvent.idOf(EventTypes.ATTENDANCE_MISSING_CHECKIN, assignmentId, 0),
                    EventTypes.ATTENDANCE_MISSING_CHECKIN, "assignment", assignmentId, 0, null,
                    List.of((String) row.get("user_id")), "Bạn chưa chấm công vào ca",
                    row.get("name") + " " + row.get("shift_date") + " bắt đầu lúc " + row.get("start_time")
                            + ". Hãy chấm công hoặc gửi đề nghị bổ sung nếu đã đi làm.", "attendance")));
        }
    }

    List<Map<String, Object>> due(LocalDateTime now) {
        return jdbc.queryForList("""
                SELECT a.id AS assignment_id, a.user_id, s.name, s.shift_date, s.start_time
                FROM assignments a JOIN shifts s ON s.id = a.shift_id
                WHERE a.status = 'confirmed' AND s.status = 'published'
                  AND DATEADD(MINUTE, DATEDIFF(MINUTE, CAST('00:00' AS TIME), s.start_time), CAST(s.shift_date AS DATETIME2))
                      BETWEEN ? AND ?
                  AND NOT EXISTS (SELECT 1 FROM attendance_records r WHERE r.shift_id = a.shift_id AND r.user_id = a.user_id)
                """, now.minusHours(12), now.minusMinutes(minutes));
    }
}
