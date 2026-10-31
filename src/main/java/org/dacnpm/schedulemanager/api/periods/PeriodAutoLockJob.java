package org.dacnpm.schedulemanager.api.periods;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tác vụ nền tự đóng kỳ khi qua hạn chót (TU-02). Tắt bằng {@code app.scheduling.enabled=false}
 * (cùng cờ với job nền của module thông báo) khi chạy test hoặc khi instance không được chạy job.
 */
@Profile("sqlserver")
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class PeriodAutoLockJob {

    private static final Logger log = LoggerFactory.getLogger(PeriodAutoLockJob.class);

    private final PeriodService service;

    public PeriodAutoLockJob(PeriodService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.periods.auto-lock-delay-ms:60000}",
               initialDelayString = "${app.periods.auto-lock-initial-delay-ms:10000}")
    public void run() {
        try {
            service.autoLockExpired();
        } catch (DataAccessException e) {
            // Lần chạy sau sẽ thử lại; API đăng ký vẫn tự chặn theo hạn chót nên không mất an toàn.
            log.warn("Auto-lock of expired periods failed; will retry on next run.", e);
        }
    }
}
