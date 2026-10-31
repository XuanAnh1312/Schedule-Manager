package org.dacnpm.schedulemanager.api.periods;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Truy cập {@code periods} và các bảng con (V5/V7). SQL tham số hóa, không nối chuỗi input.
 * Thời điểm đọc/ghi là UTC.
 */
@Profile("sqlserver")
@Repository
public class PeriodRepository {

    /** Kỳ vừa bị tác vụ nền tự khóa. */
    public record AutoLocked(String id, String name, LocalDateTime deadlineUtc) {}

    /** Nhân viên thuộc đối tượng áp dụng của kỳ. */
    public record Employee(String id, String employeeCode, String fullName) {}

    private static final String COLUMNS = """
            id, name, cycle, start_date, end_date, open_at, deadline, auto_lock,
            allow_reopen_by_admin, status, version, created_at, updated_at
            """;

    private static final RowMapper<PeriodRecord> MAPPER = (rs, i) -> new PeriodRecord(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("cycle"),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class),
            rs.getObject("open_at", LocalDateTime.class),
            rs.getObject("deadline", LocalDateTime.class),
            rs.getBoolean("auto_lock"),
            rs.getBoolean("allow_reopen_by_admin"),
            PeriodStatus.fromDb(rs.getString("status")),
            rs.getInt("version"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            List.of(), List.of(), List.of(), List.of());

    private final JdbcTemplate jdbc;

    public PeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ── Đọc ─────────────────────────────────────────────────────────────── */

    public List<PeriodRecord> findAll() {
        return withChildren(jdbc.query("SELECT " + COLUMNS + " FROM periods ORDER BY start_date DESC, id", MAPPER));
    }

    /** Kỳ nhân viên được thấy: đã công bố (khác nháp) và thuộc đối tượng áp dụng. */
    public List<PeriodRecord> findVisibleForEmployee(String userId) {
        return withChildren(jdbc.query("SELECT " + COLUMNS + """
                 FROM periods p
                WHERE p.status <> 'draft'
                  AND (NOT EXISTS (SELECT 1 FROM period_applicable_users pa WHERE pa.period_id = p.id)
                       OR EXISTS (SELECT 1 FROM period_applicable_users pa
                                   WHERE pa.period_id = p.id AND pa.user_id = ?))
                ORDER BY p.start_date DESC, p.id
                """, MAPPER, userId));
    }

    public Optional<PeriodRecord> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM periods WHERE id = ?", MAPPER, id)
                .stream().findFirst().map(this::loadChildren);
    }

    /**
     * Đọc và giữ khóa ghi trên dòng kỳ tới hết giao dịch. Mọi thao tác đăng ký và chuyển trạng thái
     * đi qua đây nên một request gửi đăng ký và tác vụ khóa kỳ không thể chen nhau.
     */
    public Optional<PeriodRecord> findByIdForUpdate(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM periods WITH (UPDLOCK, ROWLOCK) WHERE id = ?", MAPPER, id)
                .stream().findFirst().map(this::loadChildren);
    }

    /** Nhân viên đang hoạt động thuộc đối tượng áp dụng (rỗng = mọi nhân viên). */
    public List<Employee> findApplicableEmployees(String periodId) {
        return jdbc.query("""
                SELECT u.id, u.employee_code, u.full_name
                  FROM users u
                 WHERE u.role = 'user' AND u.status = 'active'
                   AND (NOT EXISTS (SELECT 1 FROM period_applicable_users pa WHERE pa.period_id = ?)
                        OR EXISTS (SELECT 1 FROM period_applicable_users pa
                                    WHERE pa.period_id = ? AND pa.user_id = u.id))
                 ORDER BY u.employee_code
                """, (rs, i) -> new Employee(rs.getString("id"), rs.getString("employee_code"),
                rs.getString("full_name")), periodId, periodId);
    }

    public boolean isApplicableActiveEmployee(String periodId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users u
                 WHERE u.id = ? AND u.role = 'user' AND u.status = 'active'
                   AND (NOT EXISTS (SELECT 1 FROM period_applicable_users pa WHERE pa.period_id = ?)
                        OR EXISTS (SELECT 1 FROM period_applicable_users pa
                                    WHERE pa.period_id = ? AND pa.user_id = u.id))
                """, Integer.class, userId, periodId, periodId);
        return count != null && count > 0;
    }

    /** Trong danh sách id, những id không phải nhân viên (role user) đang hoạt động. */
    public List<String> findInvalidEmployeeIds(List<String> userIds) {
        List<String> invalid = new ArrayList<>();
        for (String userId : userIds) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE id = ? AND role = 'user' AND status = 'active'",
                    Integer.class, userId);
            if (count == null || count == 0) {
                invalid.add(userId);
            }
        }
        return invalid;
    }

    public int countRegistrations(String periodId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM availability_registrations WHERE period_id = ?", Integer.class, periodId);
        return count == null ? 0 : count;
    }

    /* ── Ghi ─────────────────────────────────────────────────────────────── */

    public void insert(PeriodRecord p) {
        jdbc.update("""
                INSERT INTO periods (id, name, cycle, start_date, end_date, open_at, deadline,
                                     auto_lock, allow_reopen_by_admin, status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, p.id(), p.name(), p.cycle(), p.startDate(), p.endDate(), p.openAtUtc(), p.deadlineUtc(),
                p.autoLock(), p.allowReopenByAdmin(), p.status().dbValue());
        replaceChildren(p);
    }

    /** Sửa cấu hình; trả {@code false} nếu version đã đổi (người khác sửa trước). */
    public boolean update(PeriodRecord p, int expectedVersion) {
        int rows = jdbc.update("""
                UPDATE periods
                   SET name = ?, cycle = ?, start_date = ?, end_date = ?, open_at = ?, deadline = ?,
                       auto_lock = ?, allow_reopen_by_admin = ?,
                       version = version + 1, updated_at = SYSUTCDATETIME()
                 WHERE id = ? AND version = ?
                """, p.name(), p.cycle(), p.startDate(), p.endDate(), p.openAtUtc(), p.deadlineUtc(),
                p.autoLock(), p.allowReopenByAdmin(), p.id(), expectedVersion);
        if (rows == 1) {
            replaceChildren(p);
        }
        return rows == 1;
    }

    /** Đổi trạng thái (và hạn chót khi mở lại); trả {@code false} nếu version đã đổi. */
    public boolean updateStatus(String id, PeriodStatus status, LocalDateTime deadlineUtc, int expectedVersion) {
        return jdbc.update("""
                UPDATE periods
                   SET status = ?, deadline = ?, version = version + 1, updated_at = SYSUTCDATETIME()
                 WHERE id = ? AND version = ?
                """, status.dbValue(), deadlineUtc, id, expectedVersion) == 1;
    }

    public void delete(String id) {
        // period_weekdays, period_dates, period_applicable_users xóa theo ON DELETE CASCADE.
        jdbc.update("DELETE FROM periods WHERE id = ?", id);
    }

    /** Khóa mọi kỳ đang mở, bật tự khóa và đã qua hạn tại {@code nowUtc}. */
    public List<AutoLocked> lockExpired(LocalDateTime nowUtc) {
        return jdbc.query("""
                UPDATE periods
                   SET status = 'locked', version = version + 1, updated_at = SYSUTCDATETIME()
                OUTPUT inserted.id, inserted.name, inserted.deadline
                 WHERE status = 'open' AND auto_lock = 1 AND deadline IS NOT NULL AND deadline <= ?
                """, (rs, i) -> new AutoLocked(rs.getString("id"), rs.getString("name"),
                rs.getObject("deadline", LocalDateTime.class)), nowUtc);
    }

    /* ── Bảng con ────────────────────────────────────────────────────────── */

    private void replaceChildren(PeriodRecord p) {
        jdbc.update("DELETE FROM period_weekdays WHERE period_id = ?", p.id());
        jdbc.update("DELETE FROM period_dates WHERE period_id = ?", p.id());
        jdbc.update("DELETE FROM period_applicable_users WHERE period_id = ?", p.id());
        for (Integer weekday : p.requiredDaysOfWeek()) {
            jdbc.update("INSERT INTO period_weekdays (period_id, weekday) VALUES (?, ?)", p.id(), weekday);
        }
        for (LocalDate date : p.exemptDates()) {
            jdbc.update("INSERT INTO period_dates (period_id, calendar_date, date_type) VALUES (?, ?, 'exempt')",
                    p.id(), date);
        }
        for (LocalDate date : p.closedDates()) {
            jdbc.update("INSERT INTO period_dates (period_id, calendar_date, date_type) VALUES (?, ?, 'closed')",
                    p.id(), date);
        }
        for (String userId : p.applicableUserIds()) {
            jdbc.update("INSERT INTO period_applicable_users (period_id, user_id) VALUES (?, ?)", p.id(), userId);
        }
    }

    private List<PeriodRecord> withChildren(List<PeriodRecord> periods) {
        return periods.stream().map(this::loadChildren).toList();
    }

    private PeriodRecord loadChildren(PeriodRecord p) {
        List<Integer> weekdays = jdbc.queryForList(
                "SELECT weekday FROM period_weekdays WHERE period_id = ? ORDER BY weekday", Integer.class, p.id());
        List<LocalDate> exempt = new ArrayList<>();
        List<LocalDate> closed = new ArrayList<>();
        jdbc.query("SELECT calendar_date, date_type FROM period_dates WHERE period_id = ? ORDER BY calendar_date",
                rs -> {
                    LocalDate date = rs.getObject("calendar_date", LocalDate.class);
                    if ("closed".equals(rs.getString("date_type"))) {
                        closed.add(date);
                    } else {
                        exempt.add(date);
                    }
                }, p.id());
        List<String> applicable = jdbc.queryForList(
                "SELECT user_id FROM period_applicable_users WHERE period_id = ? ORDER BY user_id",
                String.class, p.id());
        return p.withChildren(weekdays, exempt, closed, applicable);
    }
}
