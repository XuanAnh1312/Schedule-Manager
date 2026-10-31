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

    // ── Luồng nháp -> chờ duyệt -> duyệt -> công bố (TW-05) ───────────────────

    public static final String PENDING_APPROVAL = "pending_approval";
    public static final String APPROVED = "approved";
    public static final String PUBLISHED = "published";
    public static final String SUPERSEDED = "superseded";

    /** Mốc thời gian/người thực hiện của luồng duyệt. */
    public record Workflow(String submittedBy, LocalDateTime submittedAt, String reviewedBy, LocalDateTime reviewedAt,
                           String reviewNote, String publishedBy, LocalDateTime publishedAt, String supersededBy) {
    }

    public Optional<Workflow> workflow(String planId) {
        return jdbc.query("""
                        SELECT submitted_by, submitted_at, reviewed_by, reviewed_at, review_note, published_by,
                               published_at, superseded_by
                        FROM schedule_plans WHERE id = ?
                        """,
                (rs, n) -> new Workflow(rs.getString("submitted_by"),
                        rs.getObject("submitted_at", LocalDateTime.class), rs.getString("reviewed_by"),
                        rs.getObject("reviewed_at", LocalDateTime.class), rs.getString("review_note"),
                        rs.getString("published_by"), rs.getObject("published_at", LocalDateTime.class),
                        rs.getString("superseded_by")),
                planId).stream().findFirst();
    }

    /** Chuyển trạng thái có điều kiện (đúng trạng thái nguồn và version); false nếu dữ liệu đã đổi. */
    public boolean submit(String id, int expectedVersion, String actorId) {
        return jdbc.update("""
                UPDATE schedule_plans SET status = 'pending_approval', submitted_by = ?, submitted_at = SYSUTCDATETIME(),
                       reviewed_by = NULL, reviewed_at = NULL, review_note = NULL,
                       version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'draft'
                """, actorId, id, expectedVersion) > 0;
    }

    /** Duyệt (pending_approval -> approved) hoặc trả về nháp (pending_approval -> draft) kèm ghi chú. */
    public boolean review(String id, int expectedVersion, String toStatus, String actorId, String note) {
        return jdbc.update("""
                UPDATE schedule_plans SET status = ?, reviewed_by = ?, reviewed_at = SYSUTCDATETIME(), review_note = ?,
                       version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'pending_approval'
                """, toStatus, actorId, note, id, expectedVersion) > 0;
    }

    public boolean markPublished(String id, int expectedVersion, String actorId) {
        return jdbc.update("""
                UPDATE schedule_plans SET status = 'published', published_by = ?, published_at = SYSUTCDATETIME(),
                       version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'approved'
                """, actorId, id, expectedVersion) > 0;
    }

    /** Các phương án đã công bố khác của kỳ chuyển sang superseded; trả về id của chúng. */
    public List<String> supersedePublished(String periodId, String newPlanId) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM schedule_plans WHERE period_id = ? AND status = 'published' AND id <> ?",
                String.class, periodId, newPlanId);
        if (!ids.isEmpty()) {
            jdbc.update("""
                    UPDATE schedule_plans SET status = 'superseded', superseded_by = ?, version = version + 1,
                           updated_at = SYSUTCDATETIME()
                    WHERE period_id = ? AND status = 'published' AND id <> ?
                    """, newPlanId, periodId, newPlanId);
        }
        return ids;
    }

    /** Tăng version khi sửa roster của nháp; false nếu version đã đổi hoặc không còn là nháp. */
    public boolean touchDraft(String id, int expectedVersion) {
        return jdbc.update("""
                UPDATE schedule_plans SET version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND status = 'draft'
                """, id, expectedVersion) > 0;
    }

    public void addPair(String planId, PlanPair pair) {
        jdbc.update("""
                INSERT INTO plan_assignments (plan_id, shift_id, user_id)
                SELECT ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM plan_assignments WHERE plan_id = ? AND shift_id = ? AND user_id = ?)
                """, planId, pair.shiftId(), pair.userId(), planId, pair.shiftId(), pair.userId());
    }

    public void removePair(String planId, PlanPair pair) {
        jdbc.update("DELETE FROM plan_assignments WHERE plan_id = ? AND shift_id = ? AND user_id = ?",
                planId, pair.shiftId(), pair.userId());
    }

    /** Chính sách thiếu người của quy tắc chung: warn (mặc định) hoặc block. */
    public String understaffedPolicy() {
        return jdbc.queryForList("""
                        SELECT TOP 1 understaffed_policy FROM schedule_rules
                        ORDER BY CASE WHEN id = 'rules_main' THEN 0 ELSE 1 END, id
                        """, String.class).stream().findFirst().orElse("warn");
    }

    /**
     * Khóa dòng kỳ tới hết giao dịch để công bố và sửa lịch chính thức của cùng kỳ chạy tuần tự (không hai người cùng
     * ghi đè lịch của nhau).
     */
    public void lockPeriod(String periodId) {
        jdbc.queryForList("SELECT id FROM periods WITH (UPDLOCK, HOLDLOCK) WHERE id = ?", String.class, periodId);
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
