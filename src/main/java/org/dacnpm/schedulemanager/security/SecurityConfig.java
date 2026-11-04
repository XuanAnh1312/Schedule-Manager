package org.dacnpm.schedulemanager.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Central Spring Security configuration.
 *
 * <p>Session policy: STATELESS — no HTTP session is created. Authentication is
 * carried entirely in the JWT supplied by the client on each request.
 *
 * <p>Public endpoints (no token required):
 * <ul>
 *   <li>POST /api/auth/login</li>
 *   <li>POST /api/auth/logout — client-side token discard only</li>
 *   <li>GET  /api/health</li>
 *   <li>GET  /api/health/database</li>
 * </ul>
 *
 * <p>Protected endpoints (valid JWT required):
 * <ul>
 *   <li>GET /api/levels — any authenticated user</li>
 *   <li>All other paths — authenticated by default</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtUtil jwtUtil;

    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * Shared {@link PasswordEncoder} bean.
     * Injected into {@link org.dacnpm.schedulemanager.api.auth.AuthService}
     * so BCrypt is not instantiated ad-hoc anywhere in the codebase.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtUtil);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(org.springframework.security.config.Customizer.withDefaults())
            // Disable CSRF — stateless JWT API, no session cookies.
            .csrf(AbstractHttpConfigurer::disable)
            // The attendance panel is embedded by the workspace on the same origin.
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))

            // Stateless: never create or use HttpSession.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Return 401 JSON-friendly response, not a redirect to a login page.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Unauthorized\"}");
                })
                // Đã đăng nhập nhưng không đủ quyền: trả 403 trực tiếp. Nếu để mặc định, Spring chuyển sang /error,
                // lượt chuyển đó không chạy lại JWT filter nên bị coi là chưa đăng nhập và trả nhầm 401.
                .accessDeniedHandler((request, response, deniedException) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Forbidden\"}");
                })
            )

            // Endpoint authorization rules.
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/workspace.html", "/attendance.html", "/js/**", "/css/**").permitAll()
                // Public: auth endpoints
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                // Public: health checks
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health/database").permitAll()
                // Trang lỗi nội bộ của Spring: không được che mã lỗi thật (403/404/400) bằng 401.
                .requestMatchers("/error").permitAll()
                // Schedule management: admin only
                .requestMatchers("/api/schedule/**").hasRole("ADMIN")
                // Self-service checks: any signed-in user, always for their own account
                .requestMatchers("/api/my/**").authenticated()
                // All other requests require authentication
                .anyRequest().authenticated()
            )

            // Place our JWT filter before Spring's default username/password filter.
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
