package org.dacnpm.schedulemanager.api.notifications;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Profile("sqlserver")
@Repository
public class NotificationSettingsRepository {

    private final JdbcTemplate jdbc;

    public NotificationSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public NotificationSettings get() {
        List<String> rows = jdbc.queryForList(
                "SELECT deadline_reminder_hours FROM notification_settings WHERE id = 'main'", String.class);
        String raw = rows.isEmpty() ? null : rows.get(0);
        List<Integer> hours = parse(raw);
        return new NotificationSettings(hours, !hours.isEmpty());
    }

    public void saveDeadlineReminderHours(List<Integer> hours, String actorId) {
        String raw = hours.isEmpty() ? null : String.join(",", hours.stream().map(String::valueOf).toList());
        jdbc.update("""
                UPDATE notification_settings
                SET deadline_reminder_hours = ?, updated_by = ?, updated_at = SYSUTCDATETIME()
                WHERE id = 'main'
                """, raw, actorId);
    }

    static List<Integer> parse(String raw) {
        List<Integer> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) result.add(Integer.parseInt(p));
        }
        return result;
    }
}
