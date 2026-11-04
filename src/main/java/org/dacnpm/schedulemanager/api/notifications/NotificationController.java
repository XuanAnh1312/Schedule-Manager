package org.dacnpm.schedulemanager.api.notifications;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Thông báo in-app (BA-05). Người dùng chỉ đọc/đánh dấu thông báo của chính mình;
 * userId luôn lấy từ JWT.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** GET /api/notifications?unreadOnly=false&page=0&size=20 */
    @GetMapping
    public NotificationPage list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return service.list(CurrentUser.require(), unreadOnly, page, size);
    }

    /** GET /api/notifications/unread-count — dùng cho chấm đỏ trên chuông. */
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", service.unreadCount(CurrentUser.require()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable String id) {
        service.markRead(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", service.markAllRead(CurrentUser.require()));
    }

    /** GET /api/notifications/settings — Admin xem mốc nhắc hạn đăng ký. */
    @GetMapping("/settings")
    public NotificationSettings getSettings() {
        return service.getSettings(CurrentUser.require());
    }

    /** PUT /api/notifications/settings  body: {"deadlineReminderHours":[48,24]} ([] = tắt). */
    @PutMapping("/settings")
    public NotificationSettings updateSettings(@RequestBody SettingsRequest request) {
        return service.updateSettings(CurrentUser.require(), request.deadlineReminderHours());
    }

    public record SettingsRequest(List<Integer> deadlineReminderHours) {}
}
