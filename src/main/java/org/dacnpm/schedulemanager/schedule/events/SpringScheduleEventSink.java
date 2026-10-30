package org.dacnpm.schedulemanager.schedule.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bản mặc định khi chưa có module thông báo: đưa sự kiện qua cơ chế sự kiện của Spring; listener chỉ chạy sau khi giao
 * dịch commit ({@link TransactionPhase#AFTER_COMMIT}), giao dịch rollback thì sự kiện bị bỏ.
 */
@Component
public class SpringScheduleEventSink implements ScheduleEventSink {

    private final ApplicationEventPublisher publisher;

    public SpringScheduleEventSink(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(ScheduleEvent event) {
        if (!event.recipients().isEmpty()) {
            publisher.publishEvent(event);
        }
    }

    /** Ghi log sự kiện đã commit; module thông báo thay bằng outbox của mình. */
    @Component
    static class AfterCommitLog {

        private static final Logger LOG = LoggerFactory.getLogger(AfterCommitLog.class);

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onCommitted(ScheduleEvent event) {
            LOG.info("Sự kiện lịch {} ({} người nhận): {}", event.eventId(), event.recipients().size(), event.title());
        }
    }
}
