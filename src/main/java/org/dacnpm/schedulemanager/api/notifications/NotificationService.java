package org.dacnpm.schedulemanager.api.notifications;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Profile("sqlserver")
@Service
public class NotificationService {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_REMINDER_HOURS = 720; // 30 ngày

    private final NotificationRepository repository;
    private final NotificationSettingsRepository settings;
    private final AuditTrail audit;

    public NotificationService(NotificationRepository repository,
                               NotificationSettingsRepository settings,
                               AuditTrail audit) {
        this.repository = repository;
        this.settings = settings;
        this.audit = audit;
    }

    public NotificationPage list(CurrentUser user, boolean unreadOnly, int page, int size) {
        if (page < 0) throw ApiException.badRequest("INVALID_PAGE", "page phải ≥ 0.");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("INVALID_PAGE_SIZE", "size phải trong khoảng 1–" + MAX_PAGE_SIZE + ".");
        }
        List<NotificationView> items = repository.findByRecipient(user.id(), unreadOnly, page * size, size);
        long total = repository.countByRecipient(user.id(), unreadOnly);
        long unread = unreadOnly ? total : repository.countByRecipient(user.id(), true);
        return new NotificationPage(items, page, size, total, unread);
    }

    public long unreadCount(CurrentUser user) {
        return repository.countByRecipient(user.id(), true);
    }

    @Transactional
    public void markRead(CurrentUser user, String notificationId) {
        if (repository.markRead(notificationId, user.id()) == 0
                && !repository.existsForRecipient(notificationId, user.id())) {
            // Không tiết lộ thông báo của người khác có tồn tại hay không.
            throw ApiException.notFound("thông báo");
        }
    }

    @Transactional
    public int markAllRead(CurrentUser user) {
        return repository.markAllRead(user.id());
    }

    public NotificationSettings getSettings(CurrentUser user) {
        user.requireAdmin();
        return settings.get();
    }

    @Transactional
    public NotificationSettings updateSettings(CurrentUser user, List<Integer> hours) {
        user.requireAdmin();
        if (hours == null) {
            throw ApiException.badRequest("REQUIRED", "Cần gửi danh sách mốc nhắc (có thể rỗng để tắt nhắc).");
        }
        TreeSet<Integer> normalized = new TreeSet<>((a, b) -> Integer.compare(b, a)); // giảm dần, bỏ trùng
        for (Integer h : hours) {
            if (h == null || h < 1 || h > MAX_REMINDER_HOURS) {
                throw ApiException.badRequest("INVALID_REMINDER_HOURS",
                        "Mỗi mốc nhắc phải là số giờ từ 1 đến " + MAX_REMINDER_HOURS + ".");
            }
            normalized.add(h);
        }
        if (normalized.size() > 5) {
            throw ApiException.badRequest("TOO_MANY_REMINDERS", "Tối đa 5 mốc nhắc.");
        }
        NotificationSettings before = settings.get();
        List<Integer> list = List.copyOf(normalized);
        settings.saveDeadlineReminderHours(list, user.id());
        audit.record("notification_settings_update", "Cập nhật mốc nhắc hạn đăng ký",
                list.isEmpty() ? "Tắt nhắc hạn" : "Nhắc trước " + list + " giờ", user.id(),
                Map.of("deadlineReminderHours", before.deadlineReminderHours()),
                Map.of("deadlineReminderHours", list), null);
        return settings.get();
    }
}
