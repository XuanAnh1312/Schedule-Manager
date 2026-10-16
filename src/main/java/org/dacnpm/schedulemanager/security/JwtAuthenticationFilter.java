package org.dacnpm.schedulemanager.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the {@code Authorization: Bearer <token>} header, validates the JWT
 * with {@link JwtUtil}, and sets the Spring Security context on success.
 *
 * <p>Missing, malformed, expired, or otherwise invalid tokens result in the
 * request continuing with an anonymous (unauthenticated) context — the
 * {@link SecurityFilterChain} rules decide whether to reject it with 401.
 * No stack trace or secret is exposed to the client.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            jwtUtil.validate(token).ifPresentOrElse(
                    claims -> setAuthentication(claims),
                    () -> log.debug("JWT validation failed for request: {} {}", request.getMethod(), request.getRequestURI())
            );
        }
        // No Authorization header → continue as anonymous; SecurityFilterChain decides.

        filterChain.doFilter(request, response);
    }

    private void setAuthentication(Claims claims) {
        String userId = claims.getSubject();
        String role = claims.get("role", String.class);

        // Spring Security expects role authorities to be prefixed with ROLE_
        String authority = (role != null) ? "ROLE_" + role.toUpperCase() : "ROLE_USER";

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority(authority))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
