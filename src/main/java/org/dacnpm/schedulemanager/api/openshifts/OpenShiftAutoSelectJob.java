package org.dacnpm.schedulemanager.api.openshifts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Job chọn người cho ca trống {@code auto} đã hết hạn nhận (BAO-02).
 *
 * <p>Mỗi ca trống xử lý trong giao dịch riêng của {@link OpenShiftService#autoSelect}: lỗi ở một ca không làm hỏng ca
 * khác, và ca lỗi giữ {@code auto_processed_at = NULL} nên lượt quét sau thử lại. Chạy song song ở nhiều instance vẫn an
 * toàn vì {@code autoSelect} khóa dòng rồi kiểm tra lại cờ đã xử lý. Bật/tắt chung bằng {@code app.scheduling.enabled}.
 */
@Profile("sqlserver")
@Component
public class OpenShiftAutoSelectJob {

    private static final Logger log = LoggerFactory.getLogger(OpenShiftAutoSelectJob.class);
    static final int BATCH = 50;

    private final OpenShiftRepository repository;
    private final OpenShiftService service;
    private final Clock clock;

    public OpenShiftAutoSelectJob(OpenShiftRepository repository, OpenShiftService service, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.open-shifts.auto-select-cron:0 * * * * *}")
    public void run() {
        LocalDateTime nowUtc = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        for (String id : repository.findAutoDue(nowUtc, BATCH)) {
            try {
                OpenShiftService.AutoSelection result = service.autoSelect(id);
                log.info("Chọn tự động ca trống {}: {}", id, result.message());
            } catch (RuntimeException e) {
                log.warn("Chọn tự động ca trống {} thất bại, sẽ thử lại ở lượt sau: {}", id, e.getMessage());
            }
        }
    }
}
