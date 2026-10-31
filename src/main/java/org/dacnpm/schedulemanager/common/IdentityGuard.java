package org.dacnpm.schedulemanager.common;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Recheck database identity on API calls so a locked account or old role cannot keep using its JWT. */
@Configuration
@Profile("sqlserver & !test")
public class IdentityGuard implements WebMvcConfigurer {
    private final JdbcTemplate jdbc;
    public IdentityGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,Object handler) throws Exception {
                var auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth==null || "anonymousUser".equals(auth.getPrincipal()))return true;
                var rows=jdbc.queryForList("SELECT role,status FROM users WHERE id=?",String.valueOf(auth.getPrincipal()));
                boolean valid=rows.size()==1 && "active".equals(rows.get(0).get("status"))
                    && auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_"+String.valueOf(rows.get(0).get("role")).toUpperCase(java.util.Locale.ROOT)));
                if(!valid){response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":\"SESSION_INVALID\",\"message\":\"Tài khoản hoặc quyền đã thay đổi. Vui lòng đăng nhập lại.\"}");}
                return valid;
            }
        }).addPathPatterns("/api/**").excludePathPatterns(
            "/api/auth/login","/api/auth/logout",
            "/api/health","/api/health/database");
    }
}
