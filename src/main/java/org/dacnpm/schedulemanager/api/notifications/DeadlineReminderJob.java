package org.dacnpm.schedulemanager.api.notifications;

import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.events.EventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Nhắc nhân viên chưa gửi đăng ký thời gian rảnh khi kỳ sắp hết hạn (BA-05, mục 3.1).
 *
 * <p>Mốc nhắc lấy từ {@code notification_settings}; chưa cấu hình → không nhắc và ghi log
 * (không tự điền giá trị mặc định — AC-13).
 *
 * <p>eventId gồm kỳ + mốc giờ + hạn chót, nên mỗi mốc chỉ nhắc một lần; nếu Tuấn/Admin
 * gia hạn kỳ, hạn chót mới tạo eventId mới và nhắc lại theo hạn mới.
 *
 * <p>Giả định: {@code periods.deadline} lưu theo UTC như các cột thời điểm khác (cần Tuấn xác nhận).
 */
@Profile("sqlserver")
@Component
public class DeadlineReminderJob {

    private static final Logger log = LoggerFactory.getLogger(DeadlineReminderJob.class);

    private final JdbcTemplate jdbc;
    private final NotificationSettingsRepository settings;
    private final DomainEventPublisher events;
    private final TransactionTemplate tx;

    public DeadlineReminderJob(JdbcTemplate jdbc, NotificationSettingsRepository settings,
                               DomainEventPublisher events, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.events = events;
        this.tx = tx;
    }

    @Scheduled(cron = "${app.notifications.deadline-cron:0 */15 * * * *}")
    public void run() {
        NotificationSettings config = settings.get();
        if (!config.configured()) {
            log.debug("Chưa cấu hình mốc nhắc hạn đăng ký — bỏ qua.");
            return;
        }
        for (Integer hours : config.deadlineReminderHours()) {
            remindForWindow(hours);
        }
    }

    /** @return số sự kiện nhắc mới được tạo */
    public int remindForWindow(int hours) {
        List<Map<String, Object>> periods = jdbc.queryForList("""
                SELECT id, name, deadline FROM periods
                WHERE status = 'open' AND deadline IS NOT NULL
                  AND deadline > SYSUTCDATETIME()
                  AND deadline <= DATEADD(HOUR, ?, SYSUTCDATETIME())
                """, hours);
        int created = 0;
        for (Map<String, Object> p : periods) {
            String periodId = (String) p.get("id");
            String name = (String) p.get("name");
            Timestamp deadline = (Timestamp) p.get("deadline");
            List<String> pending = usersWithoutSubmission(periodId);
            if (pending.isEmpty()) continue;
            String eventId = EventTypes.PERIOD_DEADLINE_REMINDER + ":" + periodId + ":" + hours + "h:"
                    + deadline.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).getEpochSecond();
            DomainEvent event = new DomainEvent(eventId, EventTypes.PERIOD_DEADLINE_REMINDER, "period", periodId,
                    0, null, pending,
                    "Sắp hết hạn đăng ký thời gian rảnh",
                    "Kỳ \"" + name + "\" sẽ hết hạn đăng ký trong khoảng " + hours
                            + " giờ tới. Bạn chưa gửi đăng ký — hãy hoàn tất trước hạn.",
                    "register");
            Boolean isNew = tx.execute(status -> events.publish(event));
            if (Boolean.TRUE.equals(isNew)) created++;
        }
        return created;
    }

    private List<String> usersWithoutSubmission(String periodId) {
        // Kỳ có danh sách áp dụng riêng → chỉ những người đó; không có → mọi nhân viên đang hoạt động.
        return jdbc.queryForList("""
                SELECT u.id FROM users u
                WHERE u.role = 'user' AND u.status = 'active'
                  AND (NOT EXISTS (SELECT 1 FROM period_applicable_users pa WHERE pa.period_id = ?)
                       OR EXISTS (SELECT 1 FROM period_applicable_users pa
                                  WHERE pa.period_id = ? AND pa.user_id = u.id))
                  AND NOT EXISTS (SELECT 1 FROM availability_registrations ar
                                  WHERE ar.period_id = ? AND ar.user_id = u.id AND ar.status = 'submitted')
                """, String.class, periodId, periodId, periodId);
    }
}
