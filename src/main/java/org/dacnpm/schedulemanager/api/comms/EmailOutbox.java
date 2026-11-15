package org.dacnpm.schedulemanager.api.comms;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

/**
 * Xếp email vào hàng đợi khi outbox xử lý một sự kiện đã commit (BAO-05).
 *
 * <ul>
 *   <li>Chỉ được gọi từ {@code OutboxDispatcher} cho sự kiện đã commit → giao dịch nghiệp vụ rollback thì không có
 *       email.</li>
 *   <li>Mỗi kênh một dòng gửi riêng: email nằm ở {@code email_deliveries}, tách khỏi thông báo in-app; lỗi SMTP không
 *       làm hỏng thông báo in-app và được retry độc lập.</li>
 *   <li>Chống trùng: (sự kiện, người nhận) tối đa 1 email; outbox chạy lại không xếp thêm.</li>
 *   <li>Người dùng đã tắt email cho loại này → không xếp, trừ loại bắt buộc hoặc thông báo chung đánh dấu bắt buộc.</li>
 *   <li>Người nhận chưa có email → ghi {@code skipped} kèm lý do để Admin thấy, không retry.</li>
 * </ul>
 */
@Profile("sqlserver")
@Component
public class EmailOutbox {

    public static final String ANNOUNCEMENT = "announcement.published";

    private final MailSettings settings;
    private final EmailRepository repository;

    public EmailOutbox(MailSettings settings, EmailRepository repository) {
        this.settings = settings;
        this.repository = repository;
    }

    /** @return số email mới được xếp hàng (kể cả skipped) */
    public int enqueue(String eventId, String eventType, String entityId, String title, String body,
                       Collection<String> recipients) {
        if (!settings.enabled()) return 0;
        boolean mandatory = settings.mandatoryTypes().contains(eventType);
        if (ANNOUNCEMENT.equals(eventType)) {
            Optional<boolean[]> flags = repository.announcementFlags(entityId);
            if (flags.isEmpty() || !flags.get()[0]) return 0;
            mandatory = mandatory || flags.get()[1];
        } else if (!settings.eventTypes().contains(eventType)) {
            return 0;
        }
        String subject = title.length() > 200 ? title.substring(0, 200) : title;
        String text = body + "\n\n—\nEmail tự động từ Schedule Manager. Đăng nhập hệ thống để xem chi tiết.";
        int queued = 0;
        for (String recipient : recipients) {
            if (!mandatory && repository.emailDisabled(recipient, eventType)) continue;
            Optional<String> email = repository.emailOf(recipient);
            queued += email.isPresent()
                    ? repository.enqueue(eventId, recipient, email.get(), subject, text, "pending", null)
                    : repository.enqueue(eventId, recipient, null, subject, text, "skipped",
                            "Người nhận chưa có địa chỉ email.");
        }
        return queued;
    }
}
