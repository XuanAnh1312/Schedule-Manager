package org.dacnpm.schedulemanager.domain.user;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Profile("sqlserver")
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserRecord> findByUsername(String username) {
        var results = jdbcTemplate.query(
                "SELECT id, username, password_hash, role, status FROM users WHERE username = ?",
                (rs, rowNum) -> new UserRecord(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("role"),
                        rs.getString("status")
                ),
                username
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<UserRecord> findById(String id) {
        var results = jdbcTemplate.query(
                "SELECT id, username, password_hash, role, status FROM users WHERE id = ?",
                (rs, rowNum) -> new UserRecord(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("role"),
                        rs.getString("status")
                ),
                id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns a safe profile map for /api/me — excludes password_hash.
     * Includes full_name, role, status, employee_code, level_id, branch_id,
     * employee_type_id, group_id, phone, email, join_date, note.
     */
    public Map<String, Object> findProfileById(String id) {
        var rows = jdbcTemplate.queryForList(
                """
                SELECT id, username, full_name, role, status,
                       employee_code, level_id, branch_id, employee_type_id,
                       group_id, phone, email, join_date, note
                FROM users WHERE id = ?
                """, id);
        if (rows.isEmpty()) return Map.of();
        var row = rows.get(0);
        // Remap snake_case to camelCase for JSON consistency
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id",             row.get("id"));
        out.put("username",       row.get("username"));
        out.put("fullName",       row.get("full_name"));
        out.put("role",           row.get("role"));
        out.put("status",         row.get("status"));
        out.put("employeeCode",   row.get("employee_code"));
        out.put("levelId",        row.get("level_id"));
        out.put("branchId",       row.get("branch_id"));
        out.put("employeeTypeId", row.get("employee_type_id"));
        out.put("groupId",        row.get("group_id"));
        out.put("phone",          row.get("phone"));
        out.put("email",          row.get("email"));
        out.put("joinDate",       row.get("join_date"));
        out.put("note",           row.get("note"));
        return out;
    }

    /** Update the bcrypt hash of an existing user. */
    public void updatePassword(String id, String newHash) {
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, updated_at = SYSUTCDATETIME() WHERE id = ?",
                newHash, id
        );
    }
}
