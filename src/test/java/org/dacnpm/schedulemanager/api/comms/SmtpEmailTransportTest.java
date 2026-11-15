package org.dacnpm.schedulemanager.api.comms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BAO-05: client SMTP tối giản nói đúng giao thức với một máy chủ SMTP giả trong tiến trình. */
class SmtpEmailTransportTest {

    /** Máy chủ SMTP giả: ghi lại lệnh và nội dung DATA; có thể từ chối RCPT. */
    static final class FakeSmtp implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        final StringBuilder data = new StringBuilder();
        final boolean rejectRecipient;
        final Thread thread;

        FakeSmtp(boolean rejectRecipient) throws IOException {
            this.rejectRecipient = rejectRecipient;
            thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = s.getOutputStream();
                write(out, "220 fake ESMTP");
                String line;
                boolean inData = false;
                while ((line = in.readLine()) != null) {
                    if (inData) {
                        if (line.equals(".")) { inData = false; write(out, "250 queued"); }
                        else data.append(line).append("\n");
                        continue;
                    }
                    commands.add(line);
                    if (line.startsWith("EHLO")) { write(out, "250-fake"); write(out, "250 AUTH LOGIN"); }
                    else if (line.equals("AUTH LOGIN")) write(out, "334 VXNlcm5hbWU6");
                    else if (commands.size() >= 2 && commands.get(commands.size() - 2).equals("AUTH LOGIN")) write(out, "334 UGFzc3dvcmQ6");
                    else if (commands.size() >= 3 && commands.get(commands.size() - 3).equals("AUTH LOGIN")) write(out, "235 ok");
                    else if (line.startsWith("MAIL FROM")) write(out, "250 ok");
                    else if (line.startsWith("RCPT TO")) write(out, rejectRecipient ? "550 no such user" : "250 ok");
                    else if (line.equals("DATA")) { write(out, "354 go"); inData = true; }
                    else if (line.equals("QUIT")) { write(out, "221 bye"); break; }
                    else write(out, "500 ?");
                }
            } catch (IOException ignored) {
                // test kết thúc
            }
        }

        private static void write(OutputStream out, String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override public void close() throws IOException { server.close(); }
    }

    FakeSmtp smtp;

    @AfterEach
    void stop() throws IOException {
        if (smtp != null) smtp.close();
    }

    static MailSettings settings(int port, String user) {
        return new MailSettings(true, "smtp", "127.0.0.1", port, user, "secret", false, "hr@cty.vn", 3,
                MailSettings.DEFAULT_TYPES, "");
    }

    @Test
    void sendsUtf8MessageWithAuthLogin() throws Exception {
        smtp = new FakeSmtp(false);
        new SmtpEmailTransport(settings(smtp.port(), "bot")).send("an@cty.vn", "Đơn nghỉ đã duyệt", "Xin chào Trần Văn An");
        smtp.thread.join(5000);

        assertTrue(smtp.commands.contains("MAIL FROM:<hr@cty.vn>"));
        assertTrue(smtp.commands.contains("RCPT TO:<an@cty.vn>"));
        assertTrue(smtp.commands.contains(Base64.getEncoder().encodeToString("bot".getBytes(StandardCharsets.UTF_8))));
        String data = smtp.data.toString();
        assertTrue(data.contains("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(
                "Đơn nghỉ đã duyệt".getBytes(StandardCharsets.UTF_8)) + "?="));
        String encodedBody = data.substring(data.indexOf("\n\n") + 2).replace("\n", "");
        assertEquals("Xin chào Trần Văn An", new String(Base64.getMimeDecoder().decode(encodedBody), StandardCharsets.UTF_8));
        assertEquals("QUIT", smtp.commands.get(smtp.commands.size() - 1));
    }

    @Test
    void withoutUsername_skipsAuth() throws Exception {
        smtp = new FakeSmtp(false);
        new SmtpEmailTransport(settings(smtp.port(), "")).send("an@cty.vn", "Chào", "Nội dung");
        smtp.thread.join(5000);
        assertFalse(smtp.commands.contains("AUTH LOGIN"));
    }

    @Test
    void rejectedRecipient_raisesAnErrorWithTheServerReply_withoutLeakingCredentials() throws Exception {
        smtp = new FakeSmtp(true);
        IOException ex = assertThrows(IOException.class,
                () -> new SmtpEmailTransport(settings(smtp.port(), "bot")).send("x@cty.vn", "A", "B"));
        assertTrue(ex.getMessage().contains("550"));
        assertFalse(ex.getMessage().contains("secret"));
    }

    @Test
    void invalidAddress_isRejectedBeforeConnecting() {
        IOException ex = assertThrows(IOException.class,
                () -> new SmtpEmailTransport(settings(1, "")).send("không phải email", "A", "B"));
        assertTrue(ex.getMessage().contains("không hợp lệ"));
    }
}
