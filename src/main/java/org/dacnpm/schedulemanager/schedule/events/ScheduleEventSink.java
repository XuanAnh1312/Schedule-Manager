package org.dacnpm.schedulemanager.schedule.events;

/**
 * Nơi phần lịch phát sự kiện. Luôn được gọi <b>bên trong</b> giao dịch đang công bố/thay phân công; bản cài đặt phải
 * bảo đảm sự kiện chỉ ra ngoài sau khi giao dịch commit (rollback thì không có thông báo "ma").
 *
 * <p>Khi gộp module thông báo, thêm bean {@code @Primary} gọi {@code DomainEventPublisher.publish(new DomainEvent(...))}
 * với đúng các trường của {@link ScheduleEvent}: publisher đó ghi outbox trong cùng giao dịch
 * ({@code Propagation.MANDATORY}) và {@code OutboxDispatcher} chỉ gửi sự kiện đã commit.
 */
public interface ScheduleEventSink {

    void publish(ScheduleEvent event);
}
