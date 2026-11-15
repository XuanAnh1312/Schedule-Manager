package org.dacnpm.schedulemanager.api.comms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gửi email trong hàng đợi (BAO-05). Claim từng email bằng UPDATE có điều kiện (an toàn nhiều instance), gửi ngoài
 * giao dịch, rồi đánh dấu sent/failed. Lỗi → thử lại sau 2^n phút; quá số lần → dead, hiện trong màn hình
 * "Sự kiện thông báo" (BAO-01) để Admin gửi lại có lý do.
 */
@Profile("sqlserver")
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final EmailRepository repository;
    private final MailSettings settings;
    private final EmailTransport transport;
    private final int batchSize;

    @org.springframework.beans.factory.annotation.Autowired
    public EmailDispatcher(EmailRepository repository, MailSettings settings,
                           @Value("${app.mail.batch-size:20}") int batchSize) {
        this(repository, settings, "smtp".equals(settings.transport()) ? new SmtpEmailTransport(settings)
                : new LogEmailTransport(), batchSize);
    }

    EmailDispatcher(EmailRepository repository, MailSettings settings, EmailTransport transport, int batchSize) {
        this.repository = repository;
        this.settings = settings;
        this.transport = transport;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-ms:10000}")
    public void poll() {
        if (!settings.enabled()) return;
        repository.recoverExpiredLeases();
        for (String id : repository.findDue(batchSize)) {
            sendOne(id);
        }
    }

    /** Gửi một email; public để Admin "gửi ngay" sau khi đặt lại. */
    public String sendOne(String id) {
        if (repository.claim(id) != 1) {
            return "skipped"; // instance khác đã nhận
        }
        EmailRepository.EmailRow row = repository.find(id).orElseThrow();
        try {
            transport.send(row.email(), row.subject(), row.body());
            repository.markSent(id);
            return "sent";
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            if (message.length() > 1000) message = message.substring(0, 1000);
            log.warn("Gửi email {} thất bại: {}", id, message);
            repository.markFailed(id, settings.maxAttempts(), message);
            return "failed";
        }
    }
}
