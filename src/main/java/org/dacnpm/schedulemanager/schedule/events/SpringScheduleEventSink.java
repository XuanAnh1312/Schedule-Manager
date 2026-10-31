package org.dacnpm.schedulemanager.schedule.events;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;

/**
 * Lưu sự kiện lịch vào outbox trong cùng giao dịch nghiệp vụ; dispatcher chỉ nhìn thấy sự kiện đã commit.
 */
@Component
@Profile("sqlserver")
public class SpringScheduleEventSink implements ScheduleEventSink {

    private final DomainEventPublisher publisher;

    public SpringScheduleEventSink(DomainEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(ScheduleEvent event) {
        if (!event.recipients().isEmpty()) {
            publisher.publish(new DomainEvent(event.eventId(), event.type(), event.entityType(), event.entityId(),
                    event.entityVersion(), event.actorId(), event.recipients(), event.title(), event.body(), event.linkRoute()));
        }
    }

}
