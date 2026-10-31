package org.dacnpm.schedulemanager.api.auth;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Auth endpoints: login, logout, change-password, and current-user (/api/me).
 *
 * <p>All actor-scoped operations extract the user ID from the validated JWT —
 * never from a client-supplied body field (AC-11).
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    /**
     * POST /api/auth/login
     * Body: { "username": "...", "password": "..." }
     * Returns 200 + { "token": "...", "tokenType": "Bearer" } on success, 401 on failure.
     */
    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password())
                .map(token -> ResponseEntity.ok((Object) new LoginResponse(token)))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Tên đăng nhập hoặc mật khẩu không đúng.",
                                     "code", "INVALID_CREDENTIALS")));
    }

    /**
     * POST /api/auth/logout
     * Stateless JWT API: no server-side session to invalidate.
     * Client must discard its token upon receiving this response.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Map<String, String>> logout() {
        return ResponseEntity.ok(Map.of("message", "Đã đăng xuất. Vui lòng xóa token khỏi client."));
    }

    /**
     * POST /api/auth/change-password
     * Body: { "currentPassword": "...", "newPassword": "..." }
     * Requires valid JWT. Actor ID is resolved from JWT, not from request body (AC-11).
     */
    @PostMapping("/auth/change-password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody ChangePasswordRequest request) {
        var actor = CurrentUser.require();
        authService.changePassword(actor.id(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(Map.of("message", "Đã đổi mật khẩu thành công."));
    }

    /**
     * GET /api/me
     * Returns the authenticated user's current profile from the database.
     * Used by the frontend to restore session and display account info.
     * Returns 401 if the account is locked or deleted (IdentityGuard also enforces this).
     */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        var actor = CurrentUser.require();
        var user = userRepository.findById(actor.id())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED", "Tài khoản không còn tồn tại."));
        if (!"active".equals(user.status())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Tài khoản đã bị khóa.", "code", "ACCOUNT_LOCKED"));
        }
        return ResponseEntity.ok(userRepository.findProfileById(actor.id()));
    }
}
