package org.dacnpm.schedulemanager.api.auth;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.domain.user.UserRecord;
import org.dacnpm.schedulemanager.domain.user.UserRepository;
import org.dacnpm.schedulemanager.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthService}.
 * No Spring context — all dependencies are mocked or constructed directly.
 */
class AuthServiceTest {

    private static final String TEST_JWT_SECRET =
            "test-secret-key-at-least-32-chars-long-for-hmac";

    private UserRepository userRepository;
    private JwtUtil jwtUtil;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository  = mock(UserRepository.class);
        jwtUtil         = new JwtUtil(TEST_JWT_SECRET, 3_600_000L);
        passwordEncoder = new BCryptPasswordEncoder();
        authService     = new AuthService(userRepository, jwtUtil, passwordEncoder);
    }

    // ── login: success ────────────────────────────────────────────────────────

    @Test
    void login_withValidCredentials_returnsJwt() {
        String raw  = "Admin@123";
        String hash = passwordEncoder.encode(raw);
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(new UserRecord("usr_001", "admin", hash, "admin", "active")));

        Optional<String> result = authService.login("admin", raw);

        assertTrue(result.isPresent());
        assertEquals(3, result.get().split("\\.").length, "Should be a 3-part JWT");
    }

    @Test
    void login_withV3SeedHashAndDocumentedDevPassword_succeeds() {
        // Hash stored in V3__seed_admin_user.sql / corrected by V4
        String v3Hash = "$2a$10$rQUEC.jn9yzcg93LB3O4zugWOYJk.rVvweKiHgHOBe3CA.Q74BfSG";
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(new UserRecord("usr_admin_001", "admin", v3Hash, "admin", "active")));

        assertTrue(authService.login("admin", "Admin@123").isPresent(),
                "login must succeed with V3/V4 seed hash and documented dev password");
    }

    // ── login: failures ───────────────────────────────────────────────────────

    @Test
    void login_withWrongPassword_returnsEmpty() {
        String hash = passwordEncoder.encode("Admin@123");
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(new UserRecord("usr_001", "admin", hash, "admin", "active")));

        assertTrue(authService.login("admin", "WrongPassword").isEmpty());
    }

    @Test
    void login_withInactiveUser_returnsEmpty() {
        String hash = passwordEncoder.encode("Admin@123");
        when(userRepository.findByUsername("janed"))
                .thenReturn(Optional.of(new UserRecord("usr_002", "janed", hash, "user", "inactive")));

        assertTrue(authService.login("janed", "Admin@123").isEmpty());
    }

    @Test
    void login_withUnknownUsername_returnsEmpty() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertTrue(authService.login("nobody", "AnyPassword").isEmpty());
    }

    @Test
    void login_wrongPasswordAndInactiveUser_returnSameEmptyOptional() {
        // Both failure modes must return empty — same signal prevents username enumeration
        String hash = passwordEncoder.encode("Correct@1");
        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(new UserRecord("usr_003", "bob", hash, "user", "inactive")));

        assertTrue(authService.login("bob", "Wrong@1").isEmpty());
        assertTrue(authService.login("bob", "Correct@1").isEmpty());
    }

    // ── changePassword: success ───────────────────────────────────────────────

    @Test
    void changePassword_withCorrectCurrentPassword_callsUpdatePassword() {
        String currentRaw  = "Old@1234";
        String currentHash = passwordEncoder.encode(currentRaw);
        when(userRepository.findById("usr_001"))
                .thenReturn(Optional.of(new UserRecord("usr_001", "alice", currentHash, "user", "active")));

        authService.changePassword("usr_001", currentRaw, "New@1234");

        // updatePassword must be called with a properly encoded new hash
        verify(userRepository).updatePassword(eq("usr_001"),
                argThat(newHash -> passwordEncoder.matches("New@1234", newHash)));
    }

    // ── changePassword: wrong current password ────────────────────────────────

    @Test
    void changePassword_withWrongCurrentPassword_throws401AndNeverUpdates() {
        String currentHash = passwordEncoder.encode("Correct@1");
        when(userRepository.findById("usr_001"))
                .thenReturn(Optional.of(new UserRecord("usr_001", "alice", currentHash, "user", "active")));

        ApiException ex = assertThrows(ApiException.class,
                () -> authService.changePassword("usr_001", "Wrong@1", "New@1234"));

        assertEquals("WRONG_PASSWORD", ex.getCode());
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(userRepository, never()).updatePassword(any(), any());
    }

    // ── changePassword: short new password ───────────────────────────────────

    @Test
    void changePassword_withShortNewPassword_throws400BeforeDbQuery() {
        // Validation should happen before any DB access
        ApiException ex = assertThrows(ApiException.class,
                () -> authService.changePassword("usr_001", "Any@123", "short"));

        assertEquals("INVALID_PASSWORD", ex.getCode());
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        // findById should not be called at all — fail fast
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).updatePassword(any(), any());
    }

    @Test
    void changePassword_withNullNewPassword_throws400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> authService.changePassword("usr_001", "Any@123", null));

        assertEquals("INVALID_PASSWORD", ex.getCode());
    }

    // ── changePassword: locked account ────────────────────────────────────────

    @Test
    void changePassword_withLockedAccount_throws401AndNeverUpdates() {
        String hash = passwordEncoder.encode("Any@1234");
        when(userRepository.findById("usr_002"))
                .thenReturn(Optional.of(new UserRecord("usr_002", "bob", hash, "user", "inactive")));

        ApiException ex = assertThrows(ApiException.class,
                () -> authService.changePassword("usr_002", "Any@1234", "New@1234"));

        assertEquals("ACCOUNT_LOCKED", ex.getCode());
        verify(userRepository, never()).updatePassword(any(), any());
    }

    // ── changePassword: user not found ────────────────────────────────────────

    @Test
    void changePassword_withNonexistentUser_throws401() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> authService.changePassword("ghost", "any", "New@1234"));

        assertEquals("UNAUTHORIZED", ex.getCode());
    }
}
