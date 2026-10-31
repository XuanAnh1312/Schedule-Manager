package org.dacnpm.schedulemanager.domain.events;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainEventTest {

    @Test
    void eventId_isDeterministic_soRepeatedPublishIsDeduplicated() {
        String a = DomainEvent.idOf(EventTypes.SHIFT_REQUEST_APPROVED, "sreq_1", 2);
        String b = DomainEvent.idOf(EventTypes.SHIFT_REQUEST_APPROVED, "sreq_1", 2);
        assertEquals(a, b);
        assertEquals("shift_request.approved:sreq_1:v2", a);
    }

    @Test
    void recipients_areCopied_andTooLongIdRejected() {
        DomainEvent e = new DomainEvent("x", "t", "e", "1", 0, null, null, "a", "b", null);
        assertEquals(List.of(), e.recipients());
        assertThrows(IllegalArgumentException.class,
                () -> new DomainEvent("x".repeat(161), "t", "e", "1", 0, null, List.of("u"), "a", "b", null));
    }
}
