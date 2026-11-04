package org.dacnpm.schedulemanager.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Đồng hồ hệ thống dùng cho kiểm tra hạn/thời điểm (inject được để test).
 *
 * <p>Ngày/giờ ca trong bảng {@code shifts} là giờ địa phương của doanh nghiệp,
 * nên Clock dùng múi giờ cấu hình {@code app.timezone} (mặc định Asia/Ho_Chi_Minh).
 * Nếu Tuấn cung cấp bean Clock chung, bean này tự lùi lại.
 */
@Configuration
public class TimeConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock businessClock(@Value("${app.timezone:Asia/Ho_Chi_Minh}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
