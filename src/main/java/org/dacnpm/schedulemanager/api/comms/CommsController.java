package org.dacnpm.schedulemanager.api.comms;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API giao tiếp nội bộ + email (BAO-05).
 *
 * <pre>
 * POST /api/announcements                         admin gửi thông báo chung theo phạm vi
 * GET  /api/announcements                         admin: đã gửi (kèm số đã đọc)
 * GET  /api/announcements/mine                    thông báo chung tôi đã nhận
 * GET  /api/announcements/{id}/reads              admin: lịch sử đọc + trạng thái email từng người
 * GET  /api/announcements/templates               admin: mẫu tin
 * POST /api/announcements/templates               admin tạo mẫu
 * PUT  /api/announcements/templates/{id}          admin sửa / tắt mẫu
 * GET  /api/notifications/preferences             tùy chọn nhận email của tôi
 * PUT  /api/notifications/preferences             đổi tùy chọn
 * GET  /api/admin/emails?status=problem|...&page=&size=   admin: email theo trạng thái
 * POST /api/admin/emails/{id}/retry               admin gửi lại email lỗi (lý do bắt buộc)
 * </pre>
 */
@Profile("sqlserver")
@RestController
public class CommsController {

    public record RetryRequest(String reason) {}

    private final AnnouncementService announcements;
    private final NotificationPreferenceService preferences;
    private final EmailAdminService emails;

    public CommsController(AnnouncementService announcements, NotificationPreferenceService preferences,
                           EmailAdminService emails) {
        this.announcements = announcements;
        this.preferences = preferences;
        this.emails = emails;
    }

    @PostMapping("/api/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementRepository.Announcement send(@RequestBody AnnouncementService.SendInput body) {
        return announcements.send(CurrentUser.require(), body);
    }

    @GetMapping("/api/announcements")
    public List<AnnouncementRepository.Announcement> list() {
        return announcements.list(CurrentUser.require());
    }

    @GetMapping("/api/announcements/mine")
    public List<Map<String, Object>> mine() {
        return announcements.mine(CurrentUser.require());
    }

    @GetMapping("/api/announcements/{id}/reads")
    public Map<String, Object> reads(@PathVariable String id) {
        return announcements.reads(CurrentUser.require(), id);
    }

    @GetMapping("/api/announcements/templates")
    public List<AnnouncementRepository.Template> templates(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return announcements.templates(CurrentUser.require(), includeInactive);
    }

    @PostMapping("/api/announcements/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementRepository.Template createTemplate(@RequestBody AnnouncementService.TemplateInput body) {
        return announcements.createTemplate(CurrentUser.require(), body);
    }

    @PutMapping("/api/announcements/templates/{id}")
    public AnnouncementRepository.Template updateTemplate(@PathVariable String id,
                                                          @RequestBody AnnouncementService.TemplateInput body) {
        return announcements.updateTemplate(CurrentUser.require(), id, body);
    }

    @GetMapping("/api/notifications/preferences")
    public Map<String, Object> preferences() {
        return preferences.mine(CurrentUser.require());
    }

    @PutMapping("/api/notifications/preferences")
    public Map<String, Object> updatePreferences(@RequestBody List<NotificationPreferenceService.UpdateInput> body) {
        return preferences.update(CurrentUser.require(), body);
    }

    @GetMapping("/api/admin/emails")
    public EmailAdminService.Page emails(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return emails.list(CurrentUser.require(), status, page, size);
    }

    @PostMapping("/api/admin/emails/{id}/retry")
    public EmailRepository.EmailRow retry(@PathVariable String id, @RequestBody(required = false) RetryRequest body) {
        return emails.retry(CurrentUser.require(), id, body == null ? null : body.reason());
    }
}
