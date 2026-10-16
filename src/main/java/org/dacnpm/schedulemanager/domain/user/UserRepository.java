package org.dacnpm.schedulemanager.domain.user;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}
