package org.dacnpm.schedulemanager.common;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * REST controller cho quản lý nhân viên và tổ chức (A-04).
 * Tất cả actor ID lấy từ JWT, không tin client-supplied userId (AC-11).
 *
 * <p>Admin: CRUD nhân viên, xem danh mục tổ chức.
 * Employee: chỉ đọc profile của chính mình qua /api/my/profile (A-03).
 */
@RestController
@Profile("sqlserver")
public class WorkspaceDirectory {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditTrail audit;

    public WorkspaceDirectory(JdbcTemplate jdbc, PasswordEncoder passwords, AuditTrail audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
    }

    /* ═══════════════════════════ ADMIN — Employee CRUD ══════════════════════════ */

    /** GET /api/workspace/employees — danh sách toàn bộ nhân viên (admin only). */
    @GetMapping("/api/workspace/employees")
    public List<Map<String, Object>> employees() {
        CurrentUser.require().requireAdmin();
        return jdbc.queryForList("""
                SELECT id, username, full_name AS fullName, employee_code AS employeeCode,
                       level_id AS levelId, status, branch_id AS branchId,
                       employee_type_id AS employeeTypeId, group_id AS groupId,
                       phone, email, join_date AS joinDate, note
                FROM users WHERE role = 'user' ORDER BY full_name
                """);
    }

    /** GET /api/workspace/employees/{id} — chi tiết 1 nhân viên (admin only). */
    @GetMapping("/api/workspace/employees/{id}")
    public Map<String, Object> employee(@PathVariable String id) {
        CurrentUser.require().requireAdmin();
        var rows = jdbc.queryForList("""
                SELECT id, username, full_name AS fullName, employee_code AS employeeCode,
                       level_id AS levelId, status, branch_id AS branchId,
                       employee_type_id AS employeeTypeId, group_id AS groupId,
                       phone, email, join_date AS joinDate, note, created_at AS createdAt, updated_at AS updatedAt
                FROM users WHERE id = ? AND role = 'user'
                """, id);
        if (rows.isEmpty()) throw ApiException.notFound("nhân viên");
        return rows.get(0);
    }

    public record EmployeeInput(
            String employeeCode, String username, String password,
            String fullName, String levelId, String status,
            String branchId, String employeeTypeId, String groupId,
            String phone, String email, String joinDate, String note) {}

    /** POST /api/workspace/employees — tạo nhân viên mới (admin only). */
    @PostMapping("/api/workspace/employees")
    @Transactional
    public Map<String, String> create(@RequestBody EmployeeInput in) {
        CurrentUser actor = CurrentUser.require();
        actor.requireAdmin();
        validate(in);
        if (in.password() == null || in.password().length() < 8)
            throw ApiException.badRequest("INVALID_PASSWORD", "Mật khẩu cần ít nhất 8 ký tự.");
        String id = Ids.next("usr");
        try {
            jdbc.update("""
                    INSERT INTO users(id, employee_code, username, password_hash, full_name, role,
                                      level_id, status, branch_id, employee_type_id, group_id,
                                      phone, email, join_date, note)
                    VALUES(?,?,?,?,?,'user',?,?,?,?,?,?,?,?,?)
                    """,
                    id, in.employeeCode(), in.username(), passwords.encode(in.password()),
                    in.fullName(), in.levelId(), in.status(),
                    in.branchId(), in.employeeTypeId(), in.groupId(),
                    in.phone(), in.email(),
                    in.joinDate() != null ? java.sql.Date.valueOf(in.joinDate()) : null,
                    in.note());
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("EMPLOYEE_CONFLICT", "Mã/tài khoản đã tồn tại hoặc danh mục không hợp lệ.");
        }
        audit.record("employee_create", "Tạo nhân viên", id, actor.id(), null,
                Map.of("id", id, "fullName", in.fullName()), null);
        return Map.of("id", id);
    }

    /** PUT /api/workspace/employees/{id} — cập nhật thông tin nhân viên (admin only). */
    @PutMapping("/api/workspace/employees/{id}")
    @Transactional
    public Map<String, String> update(@PathVariable String id, @RequestBody EmployeeInput in) {
        CurrentUser actor = CurrentUser.require();
        actor.requireAdmin();
        validate(in);
        var before = jdbc.queryForList(
                "SELECT id, full_name, status, branch_id, employee_type_id, group_id FROM users WHERE id = ? AND role = 'user'",
                id);
        if (before.isEmpty()) throw ApiException.notFound("nhân viên");
        try {
            jdbc.update("""
                    UPDATE users
                    SET employee_code = ?, username = ?, full_name = ?, level_id = ?, status = ?,
                        branch_id = ?, employee_type_id = ?, group_id = ?,
                        phone = ?, email = ?, join_date = ?, note = ?,
                        updated_at = SYSUTCDATETIME()
                    WHERE id = ? AND role = 'user'
                    """,
                    in.employeeCode(), in.username(), in.fullName(), in.levelId(), in.status(),
                    in.branchId(), in.employeeTypeId(), in.groupId(),
                    in.phone(), in.email(),
                    in.joinDate() != null ? java.sql.Date.valueOf(in.joinDate()) : null,
                    in.note(), id);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("EMPLOYEE_CONFLICT", "Mã/tài khoản đã tồn tại hoặc danh mục không hợp lệ.");
        }
        audit.record("employee_update", "Cập nhật nhân viên", id, actor.id(),
                before.get(0), Map.of("fullName", in.fullName(), "status", in.status()), null);
        return Map.of("id", id);
    }

    private static void validate(EmployeeInput in) {
        if (in.employeeCode() == null || in.employeeCode().isBlank() || in.employeeCode().length() > 30
                || in.username() == null || in.username().isBlank() || in.username().length() > 30
                || in.fullName() == null || in.fullName().isBlank() || in.fullName().length() > 100
                || !List.of("active", "inactive").contains(in.status() == null ? "" : in.status()))
            throw ApiException.badRequest("INVALID_EMPLOYEE",
                    "Kiểm tra mã nhân viên, tài khoản, họ tên và trạng thái.");
    }

    /* ═══════════════════════════ ADMIN — Catalog ═══════════════════════════════ */

    /**
     * GET /api/workspace/catalog — danh mục levels, branches, employee_types, work_groups
     * dùng để điền vào form tạo/sửa nhân viên (admin only).
     */
    @GetMapping("/api/workspace/catalog")
    public Map<String, Object> catalog() {
        CurrentUser.require().requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("levels", jdbc.queryForList(
                "SELECT id, name, color, sort_order AS sortOrder FROM levels ORDER BY sort_order"));
        result.put("branches", jdbc.queryForList(
                "SELECT id, name FROM locations ORDER BY name"));
        result.put("employeeTypes", jdbc.queryForList(
                "SELECT id, name FROM employee_types ORDER BY name"));
        result.put("groups", jdbc.queryForList(
                "SELECT id, name FROM work_groups ORDER BY name"));
        return result;
    }

    /* ═══════════════════════════ EMPLOYEE — Own Profile ════════════════════════ */

    /**
     * GET /api/my/profile — profile của nhân viên đang đăng nhập (A-03).
     * Employee chỉ đọc tài nguyên của chính mình; actor ID lấy từ JWT.
     */
    @GetMapping("/api/my/profile")
    public Map<String, Object> myProfile() {
        var actor = CurrentUser.require();
        var rows = jdbc.queryForList("""
                SELECT id, username, full_name AS fullName, employee_code AS employeeCode,
                       level_id AS levelId, status, branch_id AS branchId,
                       employee_type_id AS employeeTypeId, group_id AS groupId,
                       phone, email, join_date AS joinDate, note
                FROM users WHERE id = ?
                """, actor.id());
        if (rows.isEmpty())
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED", "Tài khoản không còn tồn tại.");
        return rows.get(0);
    }

    /* ═══════════════════════════ WorkspaceResources ════════════════════════════ */
    // WorkspaceResources bean giữ nguyên ở file riêng.
}
