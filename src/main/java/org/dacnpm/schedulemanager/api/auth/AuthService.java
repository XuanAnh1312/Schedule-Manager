package org.dacnpm.schedulemanager.api.auth;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.domain.user.UserRepository;
import org.dacnpm.schedulemanager.security.JwtUtil;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Handles authentication logic.
 *
 * <p>Only active for the {@code sqlserver} profile — tests that do not have a
 * real database should mock this service or use a test-profile stub.
 *
 * <h3>Logout policy</h3>
 * This API issues stateless JWTs. There is no server-side session to invalidate.
 * Logout is a <em>client-side</em> operation: the client must discard the token.
 * The {@code POST /api/auth/logout} endpoint acknowledges the request and
 * reminds the client to discard its token — it does NOT revoke the JWT.
 * If token revocation is required in the future, a persistent deny-list
 * (e.g. Redis set of JTI claims) should be added and tested explicitly.
 */
@Profile("sqlserver")
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    /**
     * {@code PasswordEncoder} is injected from the Spring context bean defined
     * in {@link org.dacnpm.schedulemanager.security.SecurityConfig}.
     * Using the shared bean avoids BCrypt being instantiated at varying cost
     * factors across the codebase.
     */
    public AuthService(UserRepository userRepository, JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Attempts login. Returns a signed JWT on success.
     *
     * <p>Returns {@link Optional#empty()} for all failure cases
     * (user not found, user inactive, password mismatch) to prevent
     * username enumeration attacks.
     *
     * @param username    the username supplied by the client
     * @param rawPassword the plaintext password supplied by the client
     * @return a signed JWT token, or empty if authentication fails
     */
    public Optional<String> login(String username, String rawPassword) {
        return userRepository.findByUsername(username)
                .filter(user -> "active".equals(user.status()))
                .filter(user -> passwordEncoder.matches(rawPassword, user.passwordHash()))
                .map(user -> jwtUtil.issue(user.id(), user.username(), user.role()));
    }

    /**
     * Changes the password for an authenticated user.
     * Actor ID is taken from the JWT (never from client body — AC-11).
     *
     * @param actorId     the authenticated user's ID from JWT
     * @param currentPass the user's current plaintext password for verification
     * @param newPass     the new plaintext password (min 8 chars)
     * @throws ApiException 400 if newPass is too short, 401 if currentPass is wrong
     */
    public void changePassword(String actorId, String currentPass, String newPass) {
        if (newPass == null || newPass.length() < 8) {
            throw ApiException.badRequest("INVALID_PASSWORD", "Mật khẩu mới cần ít nhất 8 ký tự.");
        }
        var user = userRepository.findById(actorId)
                .orElseThrow(() -> new ApiException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED", "Tài khoản không tồn tại."));
        if (!"active".equals(user.status())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "ACCOUNT_LOCKED", "Tài khoản đã bị khóa.");
        }
        if (!passwordEncoder.matches(currentPass, user.passwordHash())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "WRONG_PASSWORD", "Mật khẩu hiện tại không đúng.");
        }
        userRepository.updatePassword(actorId, passwordEncoder.encode(newPass));
    }
}
