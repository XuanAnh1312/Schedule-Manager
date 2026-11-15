package org.dacnpm.schedulemanager.api.comms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Cấu hình kênh email (BAO-05). Tất cả lấy từ biến môi trường / {@code .env} — không ghi mật khẩu vào Git.
 *
 * <pre>
 * MAIL_ENABLED=false            bật kênh email (mặc định tắt: chỉ thông báo in-app như trước)
 * MAIL_TRANSPORT=log            log (ghi log, dùng khi demo không có SMTP) | smtp
 * MAIL_HOST=localhost  MAIL_PORT=1025   (ví dụ Mailpit để demo)
 * MAIL_USERNAME=  MAIL_PASSWORD=  MAIL_STARTTLS=false  MAIL_FROM=no-reply@schedule-manager.local
 * </pre>
 */
@Profile("sqlserver")
@Component
public class MailSettings {

    /** Loại sự kiện có gửi email (ngoài thông báo chung có tick "gửi email"). */
    static final String DEFAULT_TYPES = "leave.approved,leave.rejected,leave.cancelled,shift_request.approved,"
            + "shift_request.rejected,open_shift.claim_approved,attendance.missing_checkin,lms.course_reminder,"
            + "hr.contract_expiring,hr.certificate_expiring";

    private final boolean enabled;
    private final String transport;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean startTls;
    private final String from;
    private final int maxAttempts;
    private final Set<String> eventTypes;
    private final Set<String> mandatoryTypes;

    public MailSettings(@Value("${app.mail.enabled:false}") boolean enabled,
                        @Value("${app.mail.transport:log}") String transport,
                        @Value("${app.mail.host:localhost}") String host,
                        @Value("${app.mail.port:1025}") int port,
                        @Value("${app.mail.username:}") String username,
                        @Value("${app.mail.password:}") String password,
                        @Value("${app.mail.starttls:false}") boolean startTls,
                        @Value("${app.mail.from:no-reply@schedule-manager.local}") String from,
                        @Value("${app.mail.max-attempts:6}") int maxAttempts,
                        @Value("${app.mail.event-types:" + DEFAULT_TYPES + "}") String eventTypes,
                        @Value("${app.mail.mandatory-types:hr.contract_expiring}") String mandatoryTypes) {
        this.enabled = enabled;
        this.transport = transport == null ? "log" : transport.trim().toLowerCase();
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.startTls = startTls;
        this.from = from;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.eventTypes = csv(eventTypes);
        this.mandatoryTypes = csv(mandatoryTypes);
    }

    static Set<String> csv(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) return result;
        Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(result::add);
        return result;
    }

    public boolean enabled() { return enabled; }
    public String transport() { return transport; }
    public String host() { return host; }
    public int port() { return port; }
    public String username() { return username; }
    public String password() { return password; }
    public boolean startTls() { return startTls; }
    public String from() { return from; }
    public int maxAttempts() { return maxAttempts; }
    public Set<String> eventTypes() { return eventTypes; }
    public Set<String> mandatoryTypes() { return mandatoryTypes; }
}
