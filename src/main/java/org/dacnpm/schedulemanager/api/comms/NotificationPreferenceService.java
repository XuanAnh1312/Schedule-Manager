package org.dacnpm.schedulemanager.api.comms;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Người dùng tắt/bật nhận email theo loại thông báo (BAO-05). Loại bắt buộc luôn gửi, không tắt được. */
@Profile("sqlserver")
@Service
public class NotificationPreferenceService {

    static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put(EmailOutbox.ANNOUNCEMENT, "Thông báo chung của công ty");
        LABELS.put("leave.approved", "Đơn nghỉ được duyệt");
        LABELS.put("leave.rejected", "Đơn nghỉ bị từ chối");
        LABELS.put("leave.cancelled", "Đơn nghỉ bị hủy");
        LABELS.put("shift_request.approved", "Đổi/thay ca được duyệt");
        LABELS.put("shift_request.rejected", "Đổi/thay ca bị từ chối");
        LABELS.put("open_shift.claim_approved", "Nhận ca trống thành công");
        LABELS.put("attendance.missing_checkin", "Nhắc chưa chấm công");
        LABELS.put("lms.course_reminder", "Nhắc khóa học (LMS)");
        LABELS.put("hr.contract_expiring", "Hợp đồng sắp hết hạn");
        LABELS.put("hr.certificate_expiring", "Chứng chỉ sắp hết hạn");
    }

    public record Preference(String type, String label, boolean emailEnabled, boolean mandatory) {}

    public record UpdateInput(String type, Boolean emailEnabled) {}

    private final JdbcTemplate jdbc;
    private final MailSettings settings;

    public NotificationPreferenceService(JdbcTemplate jdbc, MailSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Map<String, Object> mine(CurrentUser me) {
        Map<String, Boolean> stored = new LinkedHashMap<>();
        jdbc.query("SELECT notification_type, email_enabled FROM notification_preferences WHERE user_id = ?",
                rs -> { stored.put(rs.getString(1), rs.getBoolean(2)); }, me.id());
        List<Preference> items = new ArrayList<>();
        for (String type : types()) {
            boolean mandatory = settings.mandatoryTypes().contains(type);
            items.add(new Preference(type, LABELS.getOrDefault(type, type),
                    mandatory || stored.getOrDefault(type, true), mandatory));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("emailChannelEnabled", settings.enabled());
        result.put("items", items);
        return result;
    }

    @Transactional
    public Map<String, Object> update(CurrentUser me, List<UpdateInput> changes) {
        if (changes == null || changes.isEmpty()) throw ApiException.badRequest("REQUIRED", "Không có thay đổi.");
        for (UpdateInput c : changes) {
            if (c.type() == null || !types().contains(c.type()) || c.emailEnabled() == null) {
                throw ApiException.badRequest("INVALID_TYPE", "Loại thông báo không hợp lệ: " + c.type());
            }
            if (settings.mandatoryTypes().contains(c.type()) && !c.emailEnabled()) {
                throw ApiException.badRequest("MANDATORY_TYPE", "Không thể tắt email cho loại bắt buộc: "
                        + LABELS.getOrDefault(c.type(), c.type()));
            }
            int updated = jdbc.update("""
                    UPDATE notification_preferences SET email_enabled = ?, updated_at = SYSUTCDATETIME()
                    WHERE user_id = ? AND notification_type = ?
                    """, c.emailEnabled(), me.id(), c.type());
            if (updated == 0) {
                jdbc.update("INSERT INTO notification_preferences (user_id, notification_type, email_enabled) VALUES (?, ?, ?)",
                        me.id(), c.type(), c.emailEnabled());
            }
        }
        return mine(me);
    }

    private List<String> types() {
        List<String> types = new ArrayList<>();
        types.add(EmailOutbox.ANNOUNCEMENT);
        types.addAll(settings.eventTypes());
        return types.stream().distinct().toList();
    }
}
