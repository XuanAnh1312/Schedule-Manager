package org.dacnpm.schedulemanager.api.comms;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/**
 * Client SMTP tối giản (BAO-05) — đủ cho Mailpit/MailHog khi demo và máy chủ SMTP có STARTTLS + AUTH LOGIN.
 *
 * <p>Không thêm thư viện mới vào {@code pom.xml} (tránh sửa file dùng chung và tải thêm phụ thuộc). Nội dung UTF-8,
 * tiêu đề mã hóa RFC 2047, thân base64 nên tiếng Việt hiển thị đúng. Mọi phản hồi khác mã mong đợi → {@link IOException}
 * kèm phản hồi của máy chủ để ghi vào {@code last_error}.
 */
public class SmtpEmailTransport implements EmailTransport {

    static final int TIMEOUT_MS = 15_000;

    private final MailSettings settings;

    public SmtpEmailTransport(MailSettings settings) {
        this.settings = settings;
    }

    @Override
    public void send(String to, String subject, String body) throws IOException {
        if (to == null || !to.matches("[^\\s@<>]+@[^\\s@<>]+")) {
            throw new IOException("Địa chỉ email không hợp lệ: " + to);
        }
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(settings.host(), settings.port()), TIMEOUT_MS);
            plain.setSoTimeout(TIMEOUT_MS);
            Session s = new Session(plain);
            s.expect(220);
            s.command("EHLO schedule-manager", 250);
            if (settings.startTls()) {
                s.command("STARTTLS", 220);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, settings.host(), settings.port(), true);
                tls.startHandshake();
                s = new Session(tls);
                s.command("EHLO schedule-manager", 250);
            }
            if (settings.username() != null && !settings.username().isBlank()) {
                s.command("AUTH LOGIN", 334);
                s.commandSecret(b64(settings.username()), 334);
                s.commandSecret(b64(settings.password() == null ? "" : settings.password()), 235);
            }
            s.command("MAIL FROM:<" + settings.from() + ">", 250);
            s.command("RCPT TO:<" + to + ">", 250, 251);
            s.command("DATA", 354);
            s.raw(message(settings.from(), to, subject, body));
            s.command(".", 250);
            s.command("QUIT", 221);
        }
    }

    static String message(String from, String to, String subject, String body) {
        String encodedBody = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return "From: " + from + "\r\n"
                + "To: " + to + "\r\n"
                + "Subject: =?UTF-8?B?" + b64(subject) + "?=\r\n"
                + "Date: " + ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME) + "\r\n"
                + "Message-ID: <" + UUID.randomUUID() + "@schedule-manager>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + encodedBody + "\r\n";
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Một phiên đọc/ghi theo dòng trên socket hiện tại. */
    private static final class Session {
        private final BufferedReader in;
        private final OutputStream out;

        Session(Socket socket) throws IOException {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            this.out = socket.getOutputStream();
        }

        void command(String line, int... expected) throws IOException {
            raw(line + "\r\n");
            expect(line, expected);
        }

        void commandSecret(String line, int expected) throws IOException {
            // Không đưa dữ liệu đăng nhập (dù đã base64) vào thông báo lỗi.
            raw(line + "\r\n");
            expect("(thông tin đăng nhập)", expected);
        }

        void raw(String data) throws IOException {
            out.write(data.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void expect(int... codes) throws IOException {
            expect("(kết nối)", codes);
        }

        private void expect(String after, int... codes) throws IOException {
            String line;
            String last;
            do {
                line = in.readLine();
                if (line == null) throw new IOException("Máy chủ SMTP đóng kết nối sau " + after);
                last = line;
            } while (line.length() > 3 && line.charAt(3) == '-'); // phản hồi nhiều dòng
            int code;
            try {
                code = Integer.parseInt(last.substring(0, 3));
            } catch (RuntimeException e) {
                throw new IOException("Phản hồi SMTP không hợp lệ: " + last);
            }
            for (int c : codes) if (c == code) return;
            throw new IOException("SMTP từ chối " + after.split(":")[0] + ": " + last);
        }
    }
}
