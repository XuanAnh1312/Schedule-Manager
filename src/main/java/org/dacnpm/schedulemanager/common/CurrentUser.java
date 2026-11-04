package org.dacnpm.schedulemanager.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Người dùng đang gọi API, lấy từ JWT đã được {@code JwtAuthenticationFilter} xác thực.
 * Không bao giờ tin userId/role do client gửi trong body (AC-11).
 */
public record CurrentUser(String id, boolean admin) {

    public static CurrentUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Chưa đăng nhập.");
        }
        boolean isAdmin = false;
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                isAdmin = true;
                break;
            }
        }
        return new CurrentUser(String.valueOf(auth.getPrincipal()), isAdmin);
    }

    public void requireAdmin() {
        if (!admin) {
            throw ApiException.forbidden("Bạn không có quyền thực hiện thao tác này.");
        }
    }
}
