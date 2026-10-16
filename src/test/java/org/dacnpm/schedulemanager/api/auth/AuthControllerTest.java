package org.dacnpm.schedulemanager.api.auth;

import org.dacnpm.schedulemanager.security.JwtUtil;
import org.dacnpm.schedulemanager.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security contract tests for POST /api/auth/login and POST /api/auth/logout.
 *
 * <ul>
 *   <li>Both endpoints are public (no JWT required).</li>
 *   <li>Login returns 200 + JWT on success.</li>
 *   <li>Login returns 401 for wrong password (no username enumeration).</li>
 *   <li>Login returns 401 for inactive user (same generic error).</li>
 *   <li>Logout always returns 200 and reminds client to discard token.</li>
 * </ul>
 */
@WebMvcTest(AuthController.class)
@Import({JwtUtil.class, SecurityConfig.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hmac",
        "app.jwt.expiration-ms=3600000"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_isPublic_noTokenRequired() throws Exception {
        when(authService.login("admin", "Admin@123"))
                .thenReturn(Optional.of("fake.jwt.token"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void login_withValidCredentials_returns200WithToken() throws Exception {
        when(authService.login("admin", "Admin@123"))
                .thenReturn(Optional.of("fake.jwt.token"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("fake.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void login_withWrongPassword_returns401WithGenericError() throws Exception {
        when(authService.login("admin", "WrongPassword"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"WrongPassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void login_withInactiveUser_returns401WithGenericError() throws Exception {
        // AuthService returns empty for inactive users — controller sees same empty.
        when(authService.login("inactive_user", "SomePassword"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"inactive_user\",\"password\":\"SomePassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void login_withUnknownUsername_returns401WithGenericError() throws Exception {
        // Same generic error — prevents username enumeration.
        when(authService.login("unknown", "AnyPassword"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"unknown\",\"password\":\"AnyPassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_isPublic_noTokenRequired() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk());
    }

    @Test
    void logout_alwaysReturns200WithClientSideMessage() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out. Please discard your token."));
    }
}
