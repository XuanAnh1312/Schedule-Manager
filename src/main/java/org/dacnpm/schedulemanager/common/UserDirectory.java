package org.dacnpm.schedulemanager.common;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Tra cứu nhân viên tối thiểu cho module yêu cầu/nghỉ phép/thông báo.
 * Khi Anh hoàn thiện {@code /api/employees}, có thể chuyển sang dùng service của Anh.
 */
@Profile("sqlserver")
@Component
public class UserDirectory {

    public record Employee(String id, String fullName, String levelId, String role, String status) {
        public boolean activeEmployee() {
            return "user".equals(role) && "active".equals(status);
        }
    }

    private final JdbcTemplate jdbc;

    public UserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Employee> find(String id) {
        return jdbc.query("SELECT id, full_name, level_id, role, status FROM users WHERE id = ?",
                (rs, i) -> new Employee(rs.getString("id"), rs.getString("full_name"), rs.getString("level_id"),
                        rs.getString("role"), rs.getString("status")), id).stream().findFirst();
    }

    public List<Employee> activeEmployees() {
        return jdbc.query("""
                SELECT id, full_name, level_id, role, status FROM users
                WHERE role = 'user' AND status = 'active' ORDER BY full_name
                """, (rs, i) -> new Employee(rs.getString("id"), rs.getString("full_name"), rs.getString("level_id"),
                rs.getString("role"), rs.getString("status")));
    }
}
