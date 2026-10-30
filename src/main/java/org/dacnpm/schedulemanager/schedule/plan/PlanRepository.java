package org.dacnpm.schedulemanager.schedule.plan;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng schedule_plans và plan_assignments. Phương án nháp không bao giờ ghi vào bảng assignments (lịch chính thức).
 * Khóa lạc quan theo cột version.
 */
@Profile("sqlserver")
@Repository
public class PlanRepository {

    public static final String DRAFT = "draft";
    public static final String CANCELLED = "cancelled";
    /** Trạng thái chưa hoàn tất: kỳ không được lưu trữ khi còn phương án ở các trạng thái này. */
    public static final List<String> UNFINISHED = List.of("draft", "pending_approval", "approved");

    public record PlanRow(String id, String periodId, String name, String status, int version, int runCount,
                          String shiftIdsJson, String resultJson, String createdBy, LocalDateTime createdAt,
                          LocalDateTime updatedAt) {
    }

    /** Một phân công trong phương án. */
    public record PlanPair(String shiftId, String userId) {
    }

    private static final String SELECT_PLAN = """
            SELECT id, period_id, name, status, version, run_count, shift_ids_json, result_json, created_by,
                   created_at, updated_at
            FROM schedule_plans
            """;

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> periodStatus(String periodId) {
        return jdbc.queryForList("SELECT status FROM periods WHERE id = ?", String.class, periodId).stream().findFirst();
    }

    public void insert(String id, String periodId, String name, String shiftIdsJson, String resultJson, String createdBy) {
        jdbc.update("""
                INSERT INTO schedule_plans (id, period_id, name, status, version, run_count, shift_ids_json,
                                            result_json, created_by)
                VALUES (?, ?, ?, 'draft', 1, 1, ?, ?, ?)
                """, id, periodId, name, shiftIdsJson, resultJson, createdBy);
    }

    /** Ghi kết quả chạy lại nếu phương án vẫn là nháp và đúng version; false nếu không. */
    public boolean updateRun(String id, int expectedVersion, String shiftIdsJson, String resultJson) {
        return jdbc.update("""
                UPDATE schedule_plans
                SET shift_ids_json = ?, result_json = ?, version = version + 1, run_count = run_count + 1,
                    updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'draft'
                """, shiftIdsJson, resultJson, id, expectedVersion) > 0;
    }

    public boolean updateStatus(String id, int expectedVersion, String fromStatus, String toStatus) {
        return jdbc.update("""
                UPDATE schedule_plans SET status = ?, version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = ?
                """, toStatus, id, expectedVersion, fromStatus) > 0;
    }

    public Optional<PlanRow> find(String id) {
        return jdbc.query(SELECT_PLAN + " WHERE id = ?", (rs, n) -> row(rs), id).stream().findFirst();
    }

    public List<PlanRow> listByPeriod(String periodId) {
        return jdbc.query(SELECT_PLAN + " WHERE period_id = ? ORDER BY created_at DESC, id", (rs, n) -> row(rs), periodId);
    }

    public void replaceAssignments(String planId, List<PlanPair> pairs) {
        jdbc.update("DELETE FROM plan_assignments WHERE plan_id = ?", planId);
        jdbc.batchUpdate("INSERT INTO plan_assignments (plan_id, shift_id, user_id) VALUES (?, ?, ?)",
                pairs.stream().map(p -> new Object[] {planId, p.shiftId(), p.userId()}).toList());
    }

    public List<PlanPair> assignments(String planId) {
        return jdbc.query("SELECT shift_id, user_id FROM plan_assignments WHERE plan_id = ? ORDER BY shift_id, user_id",
                (rs, n) -> new PlanPair(rs.getString("shift_id"), rs.getString("user_id")), planId);
    }

    /** Mô tả các phương án chưa hoàn tất của kỳ, ví dụ "plan_x — draft" (cho SchedulePlanGate của module kỳ). */
    public List<String> unfinishedPlans(String periodId) {
        return jdbc.query("""
                        SELECT id, status FROM schedule_plans
                        WHERE period_id = ? AND status IN ('draft', 'pending_approval', 'approved')
                        ORDER BY created_at, id
                        """,
                (rs, n) -> rs.getString("id") + " — " + rs.getString("status"), periodId);
    }

    private static PlanRow row(ResultSet rs) throws SQLException {
        return new PlanRow(rs.getString("id"), rs.getString("period_id"), rs.getString("name"), rs.getString("status"),
                rs.getInt("version"), rs.getInt("run_count"), rs.getString("shift_ids_json"), rs.getString("result_json"),
                rs.getString("created_by"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
