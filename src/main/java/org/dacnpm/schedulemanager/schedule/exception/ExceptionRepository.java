package org.dacnpm.schedulemanager.schedule.exception;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bảng schedule_exceptions và user_permissions. */
@Profile("sqlserver")
@Repository
public class ExceptionRepository {

    private final JdbcTemplate jdbc;

    public ExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasPermission(String userId, String permission) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id = ? AND permission = ?",
                Integer.class, userId, permission);
        return count != null && count > 0;
    }

    public void insert(String id, String planId, String shiftId, String userId, Set<String> codes,
                       String violationsJson, String reason, String approvedBy) {
        jdbc.update("""
                INSERT INTO schedule_exceptions (id, plan_id, shift_id, user_id, violation_codes, violations_json, reason,
                                                 approved_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, planId, shiftId, userId, String.join(",", new java.util.TreeSet<>(codes)), violationsJson,
                reason, approvedBy);
    }

    /** Mã vi phạm đã có ngoại lệ còn hiệu lực cho ca/nhân viên trong phương án (planId null: lịch chính thức). */
    public Set<String> activeCodes(String planId, String shiftId, String userId) {
        Set<String> codes = new HashSet<>();
        jdbc.queryForList("""
                        SELECT violation_codes FROM schedule_exceptions
                        WHERE status = 'active' AND shift_id = ? AND user_id = ?
                          AND ((? IS NULL AND plan_id IS NULL) OR plan_id = ?)
                        """, String.class, shiftId, userId, planId, planId)
                .forEach(csv -> codes.addAll(Arrays.asList(csv.split(","))));
        return codes;
    }
}
