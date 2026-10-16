package org.dacnpm.schedulemanager.api.auth;

import org.dacnpm.schedulemanager.domain.user.UserRecord;
import org.dacnpm.schedulemanager.domain.user.UserRepository;
import org.dacnpm.schedulemanager.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthService}.
 *
 * <p>No Spring context — all dependencies are mocked or constructed directly.
 * Tests run offline (no database, no .env).
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
        userRepository = mock(UserRepository.class);
        jwtUtil = new JwtUtil(TEST_JWT_SECRET, 3_600_000L);
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(userRepository, jwtUtil, passwordEncoder);
    }

    // ── Success ───────────────────────────────────────────────────────────────

    @Test
    void login_withValidCredentials_returnsJwt() {
        String rawPassword = "Admin@123";
        String hash = passwordEncoder.encode(rawPassword);
        UserRecord activeAdmin = new UserRecord("usr_001", "admin", hash, "admin", "active");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(activeAdmin));

        Optional<String> result = authService.login("admin", rawPassword);

        assertTrue(result.isPresent(), "Login with correct credentials must return a token.");
        // Verify the token is a valid 3-part JWT (header.payload.signature).
        assertEquals(3, result.get().split("\\.").length,
                "Returned token must be a properly formatted JWT.");
    }

    // ── Failure: wrong password ────────────────────────────────────────────────

    @Test
    void login_withWrongPassword_returnsEmpty() {
        String hash = passwordEncoder.encode("Admin@123");
        UserRecord activeAdmin = new UserRecord("usr_001", "admin", hash, "admin", "active");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(activeAdmin));

        Optional<String> result = authService.login("admin", "WrongPassword");

        assertTrue(result.isEmpty(), "Login with wrong password must return empty.");
    }

    // ── Failure: inactive user ─────────────────────────────────────────────────

    @Test
    void login_withInactiveUser_returnsEmpty() {
        String hash = passwordEncoder.encode("Admin@123");
        UserRecord inactiveUser = new UserRecord("usr_002", "janed", hash, "user", "inactive");

        when(userRepository.findByUsername("janed")).thenReturn(Optional.of(inactiveUser));

        Optional<String> result = authService.login("janed", "Admin@123");

        assertTrue(result.isEmpty(), "Login for inactive user must return empty.");
    }

    // ── Failure: user not found ────────────────────────────────────────────────

    @Test
    void login_withUnknownUsername_returnsEmpty() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        Optional<String> result = authService.login("nobody", "AnyPassword");

        assertTrue(result.isEmpty(), "Login for unknown username must return empty.");
    }

    // ── No username enumeration ────────────────────────────────────────────────

    @Test
    void login_wrongPassword_andInactiveUser_returnSameEmptyOptional() {
        // Both failure modes must return empty — not different error signals.
        String hash = passwordEncoder.encode("Correct@1");
        UserRecord inactiveUser = new UserRecord("usr_003", "bob", hash, "user", "inactive");

        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(inactiveUser));

        Optional<String> wrongPass = authService.login("bob", "Wrong@1");
        Optional<String> inactiveCorrectPass = authService.login("bob", "Correct@1");

        assertTrue(wrongPass.isEmpty());
        assertTrue(inactiveCorrectPass.isEmpty());
    }

    // ── V3 seed hash verification ──────────────────────────────────────────────

    @Test
    void login_withV3SeedHashAndDocumentedDevPassword_succeeds() {
        // This test mirrors the BCrypt hash stored in V3__seed_admin_user.sql
        // (corrected by V4__fix_admin_password_hash.sql).
        // It proves that the seed hash and the documented dev password are consistent.
        String v3Hash = "$2a$10$rQUEC.jn9yzcg93LB3O4zugWOYJk.rVvweKiHgHOBe3CA.Q74BfSG";
        UserRecord adminFromDb = new UserRecord("usr_admin_001", "admin", v3Hash, "admin", "active");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminFromDb));

        Optional<String> result = authService.login("admin", "Admin@123");

        assertTrue(result.isPresent(),
                "AuthService.login must succeed when given the V3/V4 seed hash and documented dev password.");
    }
}
