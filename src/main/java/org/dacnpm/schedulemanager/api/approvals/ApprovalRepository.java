package org.dacnpm.schedulemanager.api.approvals;

import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Truy cập cấu hình và các bước duyệt (BAO-04). Mọi chuyển trạng thái bước là UPDATE có điều kiện. */
@Profile("sqlserver")
@Repository
public class ApprovalRepository {

    private static final RowMapper<ApprovalStep> STEP = (rs, i) -> new ApprovalStep(
            rs.getString("id"), rs.getString("subject_type"), rs.getString("subject_id"), rs.getInt("step_no"),
            rs.getString("approver_kind"), rs.getString("approver_id"), rs.getString("approver_name"),
            rs.getString("status"), rs.getString("decided_by"), rs.getString("decided_name"),
            ts(rs.getTimestamp("decided_at")), rs.getString("note"), rs.getInt("version"));

    private static final RowMapper<ApprovalFlow> FLOW = (rs, i) -> new ApprovalFlow(
            rs.getString("id"), rs.getString("subject_type"), rs.getString("leave_type_id"),
            rs.getString("leave_type_name"), rs.getString("mode"), rs.getString("updated_by"),
            ts(rs.getTimestamp("updated_at")), rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public ApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ── Cấu hình ───────────────────────────────────────────────────────── */

    public List<ApprovalFlow> findFlows() {
        return jdbc.query("""
                SELECT f.*, t.name AS leave_type_name FROM approval_flows f
                LEFT JOIN leave_types t ON t.id = f.leave_type_id
                ORDER BY f.subject_type, f.leave_type_id
                """, FLOW);
    }

    /** Cấu hình áp dụng: riêng theo loại nghỉ nếu có, không thì dòng mặc định (leave_type_id NULL). */
    public Optional<ApprovalFlow> findEffectiveFlow(String subjectType, String leaveTypeId) {
        return jdbc.query("""
                SELECT TOP (1) f.*, t.name AS leave_type_name FROM approval_flows f
                LEFT JOIN leave_types t ON t.id = f.leave_type_id
                WHERE f.subject_type = ? AND (f.leave_type_id = ? OR f.leave_type_id IS NULL)
                ORDER BY CASE WHEN f.leave_type_id IS NULL THEN 1 ELSE 0 END
                """, FLOW, subjectType, leaveTypeId).stream().findFirst();
    }

    public Optional<ApprovalFlow> findFlow(String subjectType, String leaveTypeId) {
        return jdbc.query("""
                SELECT f.*, t.name AS leave_type_name FROM approval_flows f
                LEFT JOIN leave_types t ON t.id = f.leave_type_id
                WHERE f.subject_type = ? AND ((? IS NULL AND f.leave_type_id IS NULL) OR f.leave_type_id = ?)
                """, FLOW, subjectType, leaveTypeId, leaveTypeId).stream().findFirst();
    }

    public void insertFlow(String subjectType, String leaveTypeId, String mode, String actorId) {
        jdbc.update("""
                INSERT INTO approval_flows (id, subject_type, leave_type_id, mode, updated_by) VALUES (?, ?, ?, ?, ?)
                """, Ids.next("flow"), subjectType, leaveTypeId, mode, actorId);
    }

    public int updateFlow(String id, int expectedVersion, String mode, String actorId) {
        return jdbc.update("""
                UPDATE approval_flows SET mode = ?, updated_by = ?, updated_at = SYSUTCDATETIME(), version = version + 1
                WHERE id = ? AND version = ?
                """, mode, actorId, id, expectedVersion);
    }

    /* ── Người dùng ─────────────────────────────────────────────────────── */

    /** Quản lý trực tiếp đang hoạt động của nhân viên (null nếu không có hoặc đã khóa/lưu trữ). */
    public Optional<String> activeManagerOf(String userId) {
        return jdbc.query("""
                SELECT m.id FROM users u JOIN users m ON m.id = u.manager_id
                WHERE u.id = ? AND m.status = 'active' AND m.archived_at IS NULL
                """, (rs, i) -> rs.getString(1), userId).stream().findFirst();
    }

    public boolean isManagerOf(String managerId, String userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ? AND manager_id = ?",
                Integer.class, userId, managerId);
        return n != null && n > 0;
    }

    public boolean hasReports(String managerId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE manager_id = ? AND archived_at IS NULL",
                Integer.class, managerId);
        return n != null && n > 0;
    }

    /* ── Bước duyệt ─────────────────────────────────────────────────────── */

    public void insertStep(String subjectType, String subjectId, int stepNo, String kind, String approverId,
                           String status, String note) {
        jdbc.update("""
                INSERT INTO approval_steps (id, subject_type, subject_id, step_no, approver_kind, approver_id, status, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.next("apst"), subjectType, subjectId, stepNo, kind, approverId, status, note);
    }

    public List<ApprovalStep> findSteps(String subjectType, String subjectId) {
        return jdbc.query("""
                SELECT s.*, a.full_name AS approver_name, d.full_name AS decided_name FROM approval_steps s
                LEFT JOIN users a ON a.id = s.approver_id
                LEFT JOIN users d ON d.id = s.decided_by
                WHERE s.subject_type = ? AND s.subject_id = ? ORDER BY s.step_no
                """, STEP, subjectType, subjectId);
    }

    /** Chuyển một bước đang chờ sang trạng thái mới; 0 = bước đã được người khác xử lý. */
    public int decideStep(String stepId, int expectedVersion, String fromStatus, String toStatus, String deciderId,
                          String note) {
        return jdbc.update("""
                UPDATE approval_steps SET status = ?, decided_by = ?, decided_at = SYSUTCDATETIME(), note = ?,
                       version = version + 1
                WHERE id = ? AND version = ? AND status = ?
                """, toStatus, deciderId, note, stepId, expectedVersion, fromStatus);
    }

    public int activateStep(String stepId) {
        return jdbc.update("UPDATE approval_steps SET status = 'pending', version = version + 1 WHERE id = ? AND status = 'waiting'",
                stepId);
    }

    /** Bỏ qua mọi bước còn mở (đơn đã có quyết định cuối hoặc bị hủy). */
    public int skipOpenSteps(String subjectType, String subjectId, String deciderId, String note) {
        return jdbc.update("""
                UPDATE approval_steps SET status = 'skipped', decided_by = ?, decided_at = SYSUTCDATETIME(), note = ?,
                       version = version + 1
                WHERE subject_type = ? AND subject_id = ? AND status IN ('waiting', 'pending')
                """, deciderId, note, subjectType, subjectId);
    }

    /** Id đối tượng có bước đang chờ đúng người duyệt này (hộp duyệt của quản lý). */
    public List<String> pendingSubjectsFor(String approverId, String subjectType) {
        return jdbc.queryForList("""
                SELECT subject_id FROM approval_steps
                WHERE approver_id = ? AND subject_type = ? AND status = 'pending' ORDER BY created_at
                """, String.class, approverId, subjectType);
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
