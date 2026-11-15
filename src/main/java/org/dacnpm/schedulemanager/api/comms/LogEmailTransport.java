package org.dacnpm.schedulemanager.api.comms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Không gửi thật, chỉ ghi log (BAO-05) — dùng khi demo chưa có máy chủ SMTP. */
public class LogEmailTransport implements EmailTransport {

    private static final Logger log = LoggerFactory.getLogger(LogEmailTransport.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("[email:log] Tới {} — {} ({} ký tự)", to, subject, body.length());
    }
}
