package org.dacnpm.schedulemanager.api.comms;

/** Gửi một email văn bản thuần (BAO-05). Lỗi → ném ngoại lệ để dispatcher ghi failed và thử lại. */
public interface EmailTransport {
    void send(String to, String subject, String body) throws Exception;
}
