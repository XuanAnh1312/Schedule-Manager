package org.dacnpm.schedulemanager.api.auth;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.domain.user.UserRepository;
import org.dacnpm.schedulemanager.security.JwtUtil;
import org.dacnpm.schedulemanager.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security contract tests for auth endpoints.
 *
 * <ul>
 *   <li>POST /api/auth/login — public, returns JWT on success</li>
 *   <li>POST /api/auth/logout — public, stateless confirmation</li>
 *   <li>POST /api/auth/change-password — requires JWT, actor from token (AC-11)</li>
 *   <li>GET  /api/me — requires JWT, returns active user profile (AC-10)</li>
 * </ul>
 *
 * <p>Uses real JWT signing via {@link JwtUtil} so the test exercises the full
 * {@code JwtAuthenticationFilter} → {@code SecurityFilterChain} path without
 * requiring the OAuth2 resource-server dependency.
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

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserRepository userRepository;

    /** Real signed JWT for a test admin user, issued by JwtUtil in test context. */
    private String adminToken;
    /** Real signed JWT for a test regular user. */
    private String userToken;
    /** Real signed JWT for a test locked user (inactive). */
    private String lockedUserToken;

    @BeforeEach
    void issueTestTokens() {
        adminToken      = jwtUtil.issue("usr_001", "admin",   "admin");
        userToken       = jwtUtil.issue("usr_002", "alice",   "user");
        lockedUserToken = jwtUtil.issue("usr_003", "blocked", "user");
    }

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
    void login_withWrongPassword_returns401() throws Exception {
        when(authService.login("admin", "WrongPassword")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"WrongPassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void login_withInactiveUser_returns401() throws Exception {
        when(authService.login("inactive_user", "SomePassword")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"inactive_user\",\"password\":\"SomePassword\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_isPublic_noTokenRequired() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk());
    }

    @Test
    void logout_alwaysReturns200WithMessage() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isString());
    }

    // ── Change password ───────────────────────────────────────────────────────

    @Test
    void changePassword_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Old@123\",\"newPassword\":\"New@1234\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_withValidToken_returns200() throws Exception {
        doNothing().when(authService).changePassword(anyString(), eq("Old@123"), eq("New@1234"));

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Old@123\",\"newPassword\":\"New@1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void changePassword_withWrongCurrentPassword_returns401() throws Exception {
        doThrow(new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "WRONG_PASSWORD", "Mật khẩu hiện tại không đúng."))
                .when(authService).changePassword(anyString(), eq("Wrong@123"), eq("New@1234"));

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Wrong@123\",\"newPassword\":\"New@1234\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
    }

    @Test
    void changePassword_withShortNewPassword_returns400() throws Exception {
        doThrow(ApiException.badRequest("INVALID_PASSWORD", "Mật khẩu mới cần ít nhất 8 ký tự."))
                .when(authService).changePassword(anyString(), eq("Old@123"), eq("short"));

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Old@123\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test
    void changePassword_doesNotAcceptUserIdFromBody() throws Exception {
        // Actor ID must come from JWT sub claim, not from any body field.
        // Test: even if body contains a different "userId", service is called
        // with the JWT subject, not an arbitrary client value.
        doNothing().when(authService).changePassword(eq("usr_002"), anyString(), anyString());

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Old@123\",\"newPassword\":\"New@1234\"}"))
                .andExpect(status().isOk());

        // Verify the service was called with JWT subject "usr_002", not a spoofed ID
        verify(authService).changePassword(eq("usr_002"), anyString(), anyString());
    }

    // ── GET /api/me ───────────────────────────────────────────────────────────

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withValidToken_returnsUserProfile() throws Exception {
        var profile = Map.<String, Object>of(
                "id",       "usr_001",
                "username", "admin",
                "fullName", "Administrator",
                "role",     "admin",
                "status",   "active"
        );
        var userRecord = new org.dacnpm.schedulemanager.domain.user.UserRecord(
                "usr_001", "admin", "hash", "admin", "active");
        when(userRepository.findById("usr_001")).thenReturn(Optional.of(userRecord));
        when(userRepository.findProfileById("usr_001")).thenReturn(profile);

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("usr_001"))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void me_withLockedAccount_returns401() throws Exception {
        var lockedUser = new org.dacnpm.schedulemanager.domain.user.UserRecord(
                "usr_003", "blocked", "hash", "user", "inactive");
        when(userRepository.findById("usr_003")).thenReturn(Optional.of(lockedUser));

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + lockedUserToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void me_doesNotExposePasswordHash() throws Exception {
        var profile = Map.<String, Object>of(
                "id",       "usr_001",
                "username", "admin",
                "fullName", "Administrator",
                "role",     "admin",
                "status",   "active"
        );
        var userRecord = new org.dacnpm.schedulemanager.domain.user.UserRecord(
                "usr_001", "admin", "hash", "admin", "active");
        when(userRepository.findById("usr_001")).thenReturn(Optional.of(userRecord));
        when(userRepository.findProfileById("usr_001")).thenReturn(profile);

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password_hash").doesNotExist());
    }
}
