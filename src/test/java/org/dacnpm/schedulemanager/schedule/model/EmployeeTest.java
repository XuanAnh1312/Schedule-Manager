package org.dacnpm.schedulemanager.schedule.model;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmployeeTest {

    @Test
    void certificateWithoutExpiryIsAllowed() {
        Map<String, LocalDate> certificates = new HashMap<>();
        certificates.put("crt_a", null);
        Employee employee = new Employee("u1", "A", true, null, 100.0, null, null, null, certificates);
        assertTrue(employee.certificateValidUntil().containsKey("crt_a"));
        assertNull(employee.certificateValidUntil().get("crt_a"));
    }
}