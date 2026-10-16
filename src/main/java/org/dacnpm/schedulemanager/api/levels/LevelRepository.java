package org.dacnpm.schedulemanager.api.levels;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("sqlserver")
@Repository
public class LevelRepository {

    private final JdbcTemplate jdbcTemplate;

    public LevelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<LevelResponse> findAll() {
        return jdbcTemplate.query("""
                SELECT id, name, description, color, sort_order
                FROM levels
                ORDER BY sort_order
                """, (resultSet, rowNumber) -> new LevelResponse(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getString("color"),
                resultSet.getInt("sort_order")));
    }
}