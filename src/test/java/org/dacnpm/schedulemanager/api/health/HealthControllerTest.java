package org.dacnpm.schedulemanager.api.health;

import org.dacnpm.schedulemanager.security.JwtUtil;
import org.dacnpm.schedulemanager.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security contract: GET /api/health is publicly accessible without any token.
 */
@WebMvcTest(HealthController.class)
@Import({JwtUtil.class, SecurityConfig.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hmac",
        "app.jwt.expiration-ms=3600000"
})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpoint_isPublic_noTokenRequired() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("schedule-manager-api"));
    }
}
