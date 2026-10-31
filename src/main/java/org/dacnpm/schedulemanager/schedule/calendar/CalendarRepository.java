package org.dacnpm.schedulemanager.schedule.calendar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dacnpm.schedulemanager.schedule.model.Period;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Truy vấn lịch (chỉ đọc) và ghi nhận đã xem/xác nhận lịch của nhân viên. */
@Profile("sqlserver")
@Repository
public class CalendarRepository {

    /** Phân công confirmed trên ca chưa hủy, kèm tên và Level người được phân. */
    public record AssignmentRow(String assignmentId, String shiftId, String userId, String fullName, String levelId,
                                int version) {
    }

    public record Person(String userId, String fullName, String levelId) {
    }

    /** Phiên bản lịch đã công bố có nhân viên này, kèm trạng thái đã xem/xác nhận. */
    public record PublishedVersion(String planId, String planName, String periodId, LocalDateTime publishedAt,
                                   LocalDateTime viewedAt, LocalDateTime confirmedAt) {
    }

    private final JdbcTemplate jdbc;

    public CalendarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public java.util.Set<String> groupMembers(String groupId) {
        return new java.util.HashSet<>(jdbc.queryForList("SELECT id FROM users WHERE group_id=?",String.class,groupId));
    }

    public Optional<Period> period(String periodId) {
        return jdbc.query("SELECT id, name, start_date, end_date FROM periods WHERE id = ?",
                (rs, n) -> new Period(rs.getString("id"), rs.getString("name"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                periodId).stream().findFirst();
    }

    public List<AssignmentRow> confirmedAssignments(LocalDate from, LocalDate to) {
        return jdbc.query("""
                        SELECT a.id, a.shift_id, a.user_id, u.full_name, u.level_id, a.version
                        FROM assignments a
                        JOIN shifts s ON s.id = a.shift_id
                        JOIN users u ON u.id = a.user_id
                        WHERE a.status = 'confirmed' AND s.cancelled_at IS NULL AND s.shift_date BETWEEN ? AND ?
                        ORDER BY s.shift_date, s.start_time, u.full_name
                        """,
                (rs, n) -> new AssignmentRow(rs.getString("id"), rs.getString("shift_id"), rs.getString("user_id"),
                        rs.getString("full_name"), rs.getString("level_id"), rs.getInt("version")),
                from, to);
    }

    public Map<String, Person> people(Collection<String> userIds) {
        Map<String, Person> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        String in = String.join(", ", java.util.Collections.nCopies(userIds.size(), "?"));
        jdbc.query("SELECT id, full_name, level_id FROM users WHERE id IN (" + in + ")", rs -> {
            result.put(rs.getString("id"), new Person(rs.getString("id"), rs.getString("full_name"),
                    rs.getString("level_id")));
        }, userIds.toArray());
        return result;
    }

    /** Phiên bản đã công bố (published) có nhân viên trong roster và thuộc kỳ giao với khoảng ngày. */
    public List<PublishedVersion> publishedVersionsOf(String userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                        SELECT p.id, p.name, p.period_id, p.published_at, k.viewed_at, k.confirmed_at
                        FROM schedule_plans p
                        JOIN periods pr ON pr.id = p.period_id
                        LEFT JOIN schedule_acknowledgements k ON k.plan_id = p.id AND k.user_id = ?
                        WHERE p.status = 'published' AND pr.start_date <= ? AND pr.end_date >= ?
                          AND EXISTS (SELECT 1 FROM plan_assignments pa WHERE pa.plan_id = p.id AND pa.user_id = ?)
                        ORDER BY p.published_at DESC
                        """,
                (rs, n) -> new PublishedVersion(rs.getString("id"), rs.getString("name"), rs.getString("period_id"),
                        rs.getObject("published_at", LocalDateTime.class), rs.getObject("viewed_at", LocalDateTime.class),
                        rs.getObject("confirmed_at", LocalDateTime.class)),
                userId, to, from, userId);
    }

    /** Phương án đang published và có nhân viên trong roster. */
    public boolean isPublishedFor(String planId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM schedule_plans p
                WHERE p.id = ? AND p.status = 'published'
                  AND EXISTS (SELECT 1 FROM plan_assignments pa WHERE pa.plan_id = p.id AND pa.user_id = ?)
                """, Integer.class, planId, userId);
        return count != null && count > 0;
    }

    /** Ghi nhận đã xem; confirmed = true thì ghi nhận cả xác nhận. Không ghi đè thời điểm đã có. */
    public void acknowledge(String userId, String planId, boolean confirmed) {
        jdbc.update("""
                MERGE schedule_acknowledgements AS t
                USING (SELECT ? AS user_id, ? AS plan_id) AS s
                ON t.user_id = s.user_id AND t.plan_id = s.plan_id
                WHEN MATCHED THEN UPDATE SET
                    viewed_at = COALESCE(t.viewed_at, SYSUTCDATETIME()),
                    confirmed_at = CASE WHEN ? = 1 THEN COALESCE(t.confirmed_at, SYSUTCDATETIME()) ELSE t.confirmed_at END
                WHEN NOT MATCHED THEN INSERT (user_id, plan_id, viewed_at, confirmed_at)
                    VALUES (s.user_id, s.plan_id, SYSUTCDATETIME(), CASE WHEN ? = 1 THEN SYSUTCDATETIME() END);
                """, userId, planId, confirmed ? 1 : 0, confirmed ? 1 : 0);
    }
}
