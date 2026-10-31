package org.dacnpm.schedulemanager.api.rules;

import org.dacnpm.schedulemanager.api.rules.RuleModels.LevelSubstitution;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Setting;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

/**
 * Truy cập {@code rule_settings}, {@code rule_changes}, {@code rule_revision} (V7) và đọc/ghi
 * {@code level_substitutions} (V5). Không bao giờ ghi vào periods, shifts hay assignments.
 */
@Profile("sqlserver")
@Repository
public class RuleRepository {

    /** Bản ghi rule_changes. JSON giữ nguyên dạng chuỗi; service tự đọc. */
    public record ChangeRow(String id, String status, int baseRevision, String reason, String itemsJson,
                            String previewJson, String createdBy, LocalDateTime createdAtUtc, String appliedBy,
                            LocalDateTime appliedAtUtc) {}

    public record EmployeeRow(String id, String employeeCode, String fullName, String role, String status) {}

    public record PeriodRow(String id, String name, String status, LocalDate startDate, LocalDate endDate) {}

    /** Dòng mẫu V5 {@code schedule_rules} — chỉ dùng làm giá trị đề xuất. */
    public record TemplateRow(BigDecimal standardHours, BigDecimal maxHoursPerDay, BigDecimal maxHoursPerWeek,
                              BigDecimal maxHoursPerPeriod, BigDecimal minRestBetweenShifts) {}

    private static final String MAIN_RULES_ID = "rules_main";

    private static final RowMapper<Setting> SETTING_MAPPER = (rs, i) -> new Setting(
            rs.getString("id"), rs.getString("param_key"), rs.getString("scope_type"), rs.getString("scope_id"),
            rs.getString("value_text"), rs.getObject("effective_from", LocalDate.class), rs.getString("status"),
            rs.getString("change_id"), rs.getString("created_by"), rs.getObject("created_at", LocalDateTime.class));

    private static final RowMapper<ChangeRow> CHANGE_MAPPER = (rs, i) -> new ChangeRow(
            rs.getString("id"), rs.getString("status"), rs.getInt("base_revision"), rs.getString("reason"),
            rs.getString("items_json"), rs.getString("preview_json"), rs.getString("created_by"),
            rs.getObject("created_at", LocalDateTime.class), rs.getString("applied_by"),
            rs.getObject("applied_at", LocalDateTime.class));

    private static final String SETTING_COLUMNS = """
            id, param_key, scope_type, scope_id, value_text, effective_from, status, change_id, created_by, created_at
            """;

    private static final String CHANGE_COLUMNS = """
            id, status, base_revision, reason, items_json, preview_json, created_by, created_at, applied_by, applied_at
            """;

    private final JdbcTemplate jdbc;

    public RuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ── Giá trị ─────────────────────────────────────────────────────────── */

    public List<Setting> findActiveSettings() {
        return jdbc.query("SELECT " + SETTING_COLUMNS + " FROM rule_settings WHERE status = 'active'", SETTING_MAPPER);
    }

    public List<Setting> findSettings(String scopeType, String scopeId, boolean includeInactive) {
        StringBuilder sql = new StringBuilder("SELECT " + SETTING_COLUMNS + " FROM rule_settings WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (!includeInactive) {
            sql.append(" AND status = 'active'");
        }
        if (scopeType != null) {
            sql.append(" AND scope_type = ?");
            args.add(scopeType);
        }
        if (scopeId != null) {
            sql.append(" AND scope_id = ?");
            args.add(scopeId);
        }
        sql.append(" ORDER BY param_key, scope_type, scope_id, effective_from DESC, created_at DESC");
        return jdbc.query(sql.toString(), SETTING_MAPPER, args.toArray());
    }

    public void insertSetting(String id, String paramKey, String scopeType, String scopeId, String value,
                              LocalDate effectiveFrom, String changeId, String createdBy) {
        jdbc.update("""
                INSERT INTO rule_settings (id, param_key, scope_type, scope_id, value_text, effective_from,
                                           status, change_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)
                """, id, paramKey, scopeType, scopeId, value, effectiveFrom, changeId, createdBy);
    }

    /** Thay giá trị cùng tham số/phạm vi/ngày hiệu lực: bản cũ chuyển superseded (giữ lịch sử). */
    public int supersede(String paramKey, String scopeType, String scopeId, LocalDate effectiveFrom, String changeId) {
        if (scopeId == null) {
            return jdbc.update("""
                    UPDATE rule_settings
                       SET status = 'superseded', ended_by_change_id = ?, ended_at = SYSUTCDATETIME()
                     WHERE status = 'active' AND param_key = ? AND scope_type = ? AND scope_id IS NULL
                       AND effective_from = ?
                    """, changeId, paramKey, scopeType, effectiveFrom);
        }
        return jdbc.update("""
                UPDATE rule_settings
                   SET status = 'superseded', ended_by_change_id = ?, ended_at = SYSUTCDATETIME()
                 WHERE status = 'active' AND param_key = ? AND scope_type = ? AND scope_id = ?
                   AND effective_from = ?
                """, changeId, paramKey, scopeType, scopeId, effectiveFrom);
    }

    /** Bỏ mọi giá trị active của tham số trong phạm vi (op remove). */
    public int removeActive(String paramKey, String scopeType, String scopeId, String changeId) {
        if (scopeId == null) {
            return jdbc.update("""
                    UPDATE rule_settings
                       SET status = 'removed', ended_by_change_id = ?, ended_at = SYSUTCDATETIME()
                     WHERE status = 'active' AND param_key = ? AND scope_type = ? AND scope_id IS NULL
                    """, changeId, paramKey, scopeType);
        }
        return jdbc.update("""
                UPDATE rule_settings
                   SET status = 'removed', ended_by_change_id = ?, ended_at = SYSUTCDATETIME()
                 WHERE status = 'active' AND param_key = ? AND scope_type = ? AND scope_id = ?
                """, changeId, paramKey, scopeType, scopeId);
    }

    /* ── Thay đổi ────────────────────────────────────────────────────────── */

    public int currentRevision() {
        Integer revision = jdbc.queryForObject("SELECT revision FROM rule_revision WHERE id = 1", Integer.class);
        return revision == null ? 0 : revision;
    }

    /** Tăng revision nếu chưa ai áp dụng thay đổi khác kể từ lúc xem trước. */
    public boolean bumpRevision(int expected) {
        return jdbc.update("UPDATE rule_revision SET revision = revision + 1 WHERE id = 1 AND revision = ?",
                expected) == 1;
    }

    public void insertChange(String id, int baseRevision, String reason, String itemsJson, String previewJson,
                             String createdBy) {
        jdbc.update("""
                INSERT INTO rule_changes (id, status, base_revision, reason, items_json, preview_json, created_by)
                VALUES (?, 'previewed', ?, ?, ?, ?, ?)
                """, id, baseRevision, reason, itemsJson, previewJson, createdBy);
    }

    public Optional<ChangeRow> findChange(String id) {
        return jdbc.query("SELECT " + CHANGE_COLUMNS + " FROM rule_changes WHERE id = ?", CHANGE_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ChangeRow> findChangeForUpdate(String id) {
        return jdbc.query("SELECT " + CHANGE_COLUMNS + " FROM rule_changes WITH (UPDLOCK, ROWLOCK) WHERE id = ?",
                CHANGE_MAPPER, id).stream().findFirst();
    }

    public List<ChangeRow> listChanges(String status, int limit) {
        if (status == null) {
            return jdbc.query("SELECT TOP (?) " + CHANGE_COLUMNS + " FROM rule_changes ORDER BY created_at DESC",
                    CHANGE_MAPPER, limit);
        }
        return jdbc.query("SELECT TOP (?) " + CHANGE_COLUMNS
                + " FROM rule_changes WHERE status = ? ORDER BY created_at DESC", CHANGE_MAPPER, limit, status);
    }

    public void markApplied(String id, String appliedBy) {
        jdbc.update("""
                UPDATE rule_changes SET status = 'applied', applied_by = ?, applied_at = SYSUTCDATETIME()
                 WHERE id = ?
                """, appliedBy, id);
    }

    public void markCancelled(String id) {
        jdbc.update("UPDATE rule_changes SET status = 'cancelled' WHERE id = ?", id);
    }

    /* ── Dữ liệu tham chiếu ──────────────────────────────────────────────── */

    public Optional<EmployeeRow> findUser(String id) {
        return jdbc.query("SELECT id, employee_code, full_name, role, status FROM users WHERE id = ?",
                (rs, i) -> new EmployeeRow(rs.getString("id"), rs.getString("employee_code"),
                        rs.getString("full_name"), rs.getString("role"), rs.getString("status")), id)
                .stream().findFirst();
    }

    public Optional<org.dacnpm.schedulemanager.api.rules.RuleModels.ScopeContext> scopeContext(String userId) {
        return jdbc.query("SELECT id,branch_id,employee_type_id,group_id FROM users WHERE id=?",
            (rs,n) -> new org.dacnpm.schedulemanager.api.rules.RuleModels.ScopeContext(rs.getString(1),
                rs.getString(2),rs.getString(3),rs.getString(4),null),userId).stream().findFirst();
    }

    public boolean scopeExists(RuleScopeType scope, String id) {
        String table = switch(scope) { case BRANCH -> "locations"; case GROUP -> "work_groups";
            case EMPLOYEE_TYPE -> "employee_types"; default -> throw new IllegalArgumentException("Invalid scope"); };
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=?",Integer.class,id);
        return count!=null && count>0;
    }

    public List<EmployeeRow> findActiveEmployees() {
        return jdbc.query("""
                SELECT id, employee_code, full_name, role, status FROM users
                 WHERE role = 'user' AND status = 'active' ORDER BY employee_code
                """, (rs, i) -> new EmployeeRow(rs.getString("id"), rs.getString("employee_code"),
                rs.getString("full_name"), rs.getString("role"), rs.getString("status")));
    }

    public Set<String> findLevelIds() {
        return new HashSet<>(jdbc.queryForList("SELECT id FROM levels", String.class));
    }

    public List<LevelSubstitution> findLevelSubstitutions() {
        return jdbc.query("""
                SELECT higher_level_id, lower_level_id FROM level_substitutions
                 WHERE rules_id = ? ORDER BY higher_level_id, lower_level_id
                """, (rs, i) -> new LevelSubstitution(rs.getString("higher_level_id"),
                rs.getString("lower_level_id")), MAIN_RULES_ID);
    }

    public boolean mainRulesRowExists() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_rules WHERE id = ?", Integer.class,
                MAIN_RULES_ID);
        return count != null && count > 0;
    }

    /** Bảng runtime dùng chung với kiểm tra phân công; thay toàn bộ cặp của rules_main. */
    public void replaceLevelSubstitutions(List<LevelSubstitution> pairs) {
        jdbc.update("DELETE FROM level_substitutions WHERE rules_id = ?", MAIN_RULES_ID);
        for (LevelSubstitution pair : pairs) {
            jdbc.update("INSERT INTO level_substitutions (rules_id, higher_level_id, lower_level_id) VALUES (?, ?, ?)",
                    MAIN_RULES_ID, pair.higherLevelId(), pair.lowerLevelId());
        }
    }

    public Optional<TemplateRow> findTemplate() {
        return jdbc.query("""
                SELECT standard_hours, max_hours_per_day, max_hours_per_week, max_hours_per_period,
                       min_rest_between_shifts
                  FROM schedule_rules WHERE id = ?
                """, (rs, i) -> new TemplateRow(rs.getBigDecimal("standard_hours"),
                rs.getBigDecimal("max_hours_per_day"), rs.getBigDecimal("max_hours_per_week"),
                rs.getBigDecimal("max_hours_per_period"), rs.getBigDecimal("min_rest_between_shifts")),
                MAIN_RULES_ID).stream().findFirst();
    }

    /** Kỳ đã công bố (khác nháp) còn kéo dài tới hoặc sau {@code from}. */
    public List<PeriodRow> findPeriodsEndingOnOrAfter(LocalDate from) {
        return jdbc.query("""
                SELECT id, name, status, start_date, end_date FROM periods
                 WHERE status <> 'draft' AND end_date >= ?
                 ORDER BY start_date
                """, (rs, i) -> new PeriodRow(rs.getString("id"), rs.getString("name"), rs.getString("status"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)), from);
    }

    public Optional<PeriodRow> findPeriod(String id) {
        return jdbc.query("SELECT id, name, status, start_date, end_date FROM periods WHERE id = ?",
                (rs, i) -> new PeriodRow(rs.getString("id"), rs.getString("name"), rs.getString("status"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)), id)
                .stream().findFirst();
    }

    /** Có thay đổi đã áp dụng cho tham số này chưa (để biết Level thay thế đã được xác nhận). */
    public boolean hasAppliedSetting(String paramKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rule_settings WHERE param_key = ?",
                Integer.class, paramKey);
        return count != null && count > 0;
    }
}
