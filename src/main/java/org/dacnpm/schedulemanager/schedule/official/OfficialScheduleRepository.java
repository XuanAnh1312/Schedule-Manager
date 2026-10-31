package org.dacnpm.schedulemanager.schedule.official;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lịch chính thức: bảng assignments và trạng thái công bố của shifts. Mọi thao tác ghi có điều kiện (version/trạng thái/
 * người đang giữ) để hai người không ghi đè thay đổi của nhau; false/rỗng nghĩa là dữ liệu đã đổi.
 */
@Profile("sqlserver")
@Repository
public class OfficialScheduleRepository {

    /** Phân công chính thức kèm thông tin ca. */
    public record OfficialAssignment(String id, String shiftId, String userId, String status, int version,
                                     String shiftName, LocalDate shiftDate, LocalTime startTime, LocalTime endTime,
                                     String shiftStatus) {
        public boolean confirmed() {
            return "confirmed".equals(status);
        }
    }

    private static final String SELECT = """
            SELECT a.id, a.shift_id, a.user_id, a.status, a.version, s.name, s.shift_date, s.start_time, s.end_time,
                   s.status AS shift_status
            FROM assignments a JOIN shifts s ON s.id = a.shift_id
            """;

    private final JdbcTemplate jdbc;

    public OfficialScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OfficialAssignment> find(String assignmentId) {
        return jdbc.query(SELECT + " WHERE a.id = ?", (rs, n) -> map(rs), assignmentId).stream().findFirst();
    }

    /** Phân công confirmed của các ca. */
    public List<OfficialAssignment> confirmedByShifts(Collection<String> shiftIds) {
        if (shiftIds.isEmpty()) {
            return List.of();
        }
        String in = String.join(", ", java.util.Collections.nCopies(shiftIds.size(), "?"));
        return jdbc.query(SELECT + " WHERE a.status = 'confirmed' AND a.shift_id IN (" + in + ") ORDER BY a.shift_id, a.user_id",
                (rs, n) -> map(rs), shiftIds.toArray());
    }

    /** Phân công confirmed của một người trong khoảng ngày (hai đầu tính cả). */
    public List<OfficialAssignment> confirmedOfUser(String userId, LocalDate from, LocalDate to) {
        return jdbc.query(SELECT + """
                         WHERE a.user_id = ? AND a.status = 'confirmed' AND s.cancelled_at IS NULL
                           AND s.shift_date BETWEEN ? AND ?
                         ORDER BY s.shift_date, s.start_time
                        """, (rs, n) -> map(rs), userId, from, to);
    }

    /**
     * Thêm người vào ca. Nếu đã có dòng bị hủy cho cùng ca/người (UNIQUE shift_id, user_id) thì kích hoạt lại dòng đó.
     *
     * @return id phân công
     */
    public String addOrReactivate(String shiftId, String userId, String actorId, String reason, String sourcePlanId) {
        List<String> existing = jdbc.queryForList("SELECT id FROM assignments WHERE shift_id = ? AND user_id = ?",
                String.class, shiftId, userId);
        if (!existing.isEmpty()) {
            String id = existing.get(0);
            int updated = jdbc.update("""
                    UPDATE assignments SET status = 'confirmed', assigned_by = ?, assigned_at = SYSUTCDATETIME(),
                           reason = ?, source_plan_id = ?, version = version + 1, updated_at = SYSUTCDATETIME()
                    WHERE id = ? AND status = 'cancelled'
                    """, actorId, reason, sourcePlanId, id);
            if (updated == 0) {
                throw new org.dacnpm.schedulemanager.schedule.plan.PlanException(
                        org.dacnpm.schedulemanager.schedule.plan.PlanException.ALREADY_ASSIGNED, 409,
                        "Nhân viên " + userId + " đã được phân vào ca " + shiftId + ".");
            }
            return id;
        }
        String id = "asgn_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO assignments (id, shift_id, user_id, assigned_by, reason, status, source_plan_id)
                VALUES (?, ?, ?, ?, ?, 'confirmed', ?)
                """, id, shiftId, userId, actorId, reason, sourcePlanId);
        return id;
    }

    /** Hủy phân công nếu vẫn confirmed, đúng người giữ và đúng version. */
    public boolean cancel(String assignmentId, String expectedUserId, int expectedVersion, String reason) {
        return jdbc.update("""
                UPDATE assignments SET status = 'cancelled', reason = ?, version = version + 1,
                       updated_at = SYSUTCDATETIME()
                WHERE id = ? AND user_id = ? AND version = ? AND status = 'confirmed'
                """, reason, assignmentId, expectedUserId, expectedVersion) > 0;
    }

    /** Đánh dấu các ca đã công bố. */
    public void markShiftsPublished(Collection<String> shiftIds) {
        for (String shiftId : shiftIds) {
            jdbc.update("""
                    UPDATE shifts SET status = 'published', version = version + 1, updated_at = SYSUTCDATETIME()
                    WHERE id = ? AND cancelled_at IS NULL
                    """, shiftId);
        }
    }

    private static OfficialAssignment map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OfficialAssignment(rs.getString("id"), rs.getString("shift_id"), rs.getString("user_id"),
                rs.getString("status"), rs.getInt("version"), rs.getString("name"),
                rs.getObject("shift_date", LocalDate.class), rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class), rs.getString("shift_status"));
    }
}
