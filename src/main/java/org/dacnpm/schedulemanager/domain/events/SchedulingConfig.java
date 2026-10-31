package org.dacnpm.schedulemanager.domain.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật job nền (dispatch outbox, nhắc hạn đăng ký). Tắt bằng {@code app.scheduling.enabled=false}
 * khi chạy test hoặc khi chỉ một instance được phép chạy job.
 */
@Profile("sqlserver")
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
