package org.dacnpm.schedulemanager.api.leave;

import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Truy cập sổ phép (BAO-03). Mọi thay đổi số dư là một câu UPDATE có điều kiện (version + đủ số dư) đi kèm một dòng
 * sổ cái trong cùng giao dịch của bên gọi; 0 dòng cập nhật = dữ liệu đã đổi hoặc không đủ phép.
 */
@Profile("sqlserver")
@Repository
public class LeaveBalanceRepository {

    private static final String BALANCE_SELECT = """
            SELECT b.*, u.full_name AS user_name, t.name AS type_name,
                   b.entitled + b.adjusted - b.used - b.pending AS remaining
            FROM leave_balances b
            JOIN users u ON u.id = b.user_id
            JOIN leave_types t ON t.id = b.leave_type_id
            """;

    private static final RowMapper<LeaveBalanceView> BALANCE = (rs, i) -> new LeaveBalanceView(
            rs.getString("id"), rs.getString("user_id"), rs.getString("user_name"), rs.getInt("balance_year"),
            rs.getString("leave_type_id"), rs.getString("type_name"), rs.getString("policy_id"),
            rs.getBigDecimal("entitled"), rs.getBigDecimal("adjusted"), rs.getBigDecimal("used"),
            rs.getBigDecimal("pending"), rs.getBigDecimal("remaining"), rs.getInt("version"),
            ts(rs.getTimestamp("updated_at")));

    private static final String POLICY_SELECT = """
            SELECT p.*, t.name AS type_name, e.name AS employee_type_name
            FROM leave_policies p
            JOIN leave_types t ON t.id = p.leave_type_id
            LEFT JOIN employee_types e ON e.id = p.employee_type_id
            """;

    private static final RowMapper<LeavePolicyView> POLICY = (rs, i) -> new LeavePolicyView(
            rs.getString("id"), rs.getString("leave_type_id"), rs.getString("type_name"),
            rs.getString("employee_type_id"), rs.getString("employee_type_name"), rs.getBigDecimal("days_per_year"),
            rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
            rs.getString("note"), rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public LeaveBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ── Chính sách ─────────────────────────────────────────────────────── */

    public List<LeavePolicyView> findPolicies(String leaveTypeId) {
        if (leaveTypeId == null) {
            return jdbc.query(POLICY_SELECT + " ORDER BY p.leave_type_id, p.employee_type_id, p.effective_from DESC", POLICY);
        }
        return jdbc.query(POLICY_SELECT + " WHERE p.leave_type_id = ? ORDER BY p.employee_type_id, p.effective_from DESC",
                POLICY, leaveTypeId);
    }

    public Optional<LeavePolicyView> findPolicy(String id) {
        return jdbc.query(POLICY_SELECT + " WHERE p.id = ?", POLICY, id).stream().findFirst();
    }

    /** Chính sách cùng loại nghỉ + loại nhân viên có khoảng hiệu lực giao với khoảng mới (để chặn chồng chéo). */
    public boolean policyOverlaps(String leaveTypeId, String employeeTypeId, LocalDate from, LocalDate to) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM leave_policies
                WHERE leave_type_id = ?
                  AND ((? IS NULL AND employee_type_id IS NULL) OR employee_type_id = ?)
                  AND effective_from <= COALESCE(?, '9999-12-31')
                  AND COALESCE(effective_to, '9999-12-31') >= ?
                """, Integer.class, leaveTypeId, employeeTypeId, employeeTypeId, to, from);
        return n != null && n > 0;
    }

    public void insertPolicy(String id, String leaveTypeId, String employeeTypeId, BigDecimal days, LocalDate from,
                             LocalDate to, String note, String actorId) {
        jdbc.update("""
                INSERT INTO leave_policies (id, leave_type_id, employee_type_id, days_per_year, effective_from,
                                            effective_to, note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, leaveTypeId, employeeTypeId, days, from, to, note, actorId);
    }

    /** Kết thúc hiệu lực chính sách (không xóa, để giữ căn cứ của số dư đã cấp). */
    public int endPolicy(String id, int expectedVersion, LocalDate effectiveTo) {
        return jdbc.update("""
                UPDATE leave_policies SET effective_to = ?, version = version + 1
                WHERE id = ? AND version = ? AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                """, effectiveTo, id, expectedVersion, effectiveTo, effectiveTo);
    }

    public Optional<String> employeeTypeOf(String userId) {
        return jdbc.query("SELECT employee_type_id FROM users WHERE id = ?", (rs, i) -> rs.getString(1), userId)
                .stream().findFirst();
    }

    public boolean employeeTypeExists(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM employee_types WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /* ── Số dư ──────────────────────────────────────────────────────────── */

    public Optional<LeaveBalanceView> findBalance(String userId, int year, String leaveTypeId) {
        return jdbc.query(BALANCE_SELECT + " WHERE b.user_id = ? AND b.balance_year = ? AND b.leave_type_id = ?",
                BALANCE, userId, year, leaveTypeId).stream().findFirst();
    }

    public Optional<LeaveBalanceView> findBalanceById(String id) {
        return jdbc.query(BALANCE_SELECT + " WHERE b.id = ?", BALANCE, id).stream().findFirst();
    }

    public List<LeaveBalanceView> findBalances(String userId, Integer year) {
        StringBuilder sql = new StringBuilder(BALANCE_SELECT).append(" WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (userId != null) {
            sql.append(" AND b.user_id = ?");
            args.add(userId);
        }
        if (year != null) {
            sql.append(" AND b.balance_year = ?");
            args.add(year);
        }
        sql.append(" ORDER BY u.full_name, b.balance_year DESC, t.sort_order");
        return jdbc.query(sql.toString(), BALANCE, args.toArray());
    }

    /** Khóa dòng số dư (UPDLOCK) tới hết giao dịch: hai thao tác trên cùng số dư chạy tuần tự. */
    public void lockBalance(String balanceId) {
        jdbc.queryForList("SELECT id FROM leave_balances WITH (UPDLOCK, ROWLOCK) WHERE id = ?", String.class, balanceId);
    }

    public void insertBalance(String id, String userId, int year, String leaveTypeId, String policyId,
                              BigDecimal entitled) {
        jdbc.update("""
                INSERT INTO leave_balances (id, user_id, balance_year, leave_type_id, policy_id, entitled)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, userId, year, leaveTypeId, policyId, entitled);
    }

    /** Giữ chỗ cho đơn chờ duyệt nếu còn đủ phép. */
    public int reserve(String balanceId, BigDecimal days) {
        return jdbc.update("""
                UPDATE leave_balances SET pending = pending + ?, version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND entitled + adjusted - used - pending >= ?
                """, days, balanceId, days);
    }

    /** Trả chỗ đã giữ (đơn bị từ chối/hủy khi đang chờ). */
    public int release(String balanceId, BigDecimal days) {
        return jdbc.update("""
                UPDATE leave_balances SET pending = pending - ?, version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND pending >= ?
                """, days, balanceId, days);
    }

    /** Chuyển phần giữ chỗ thành đã dùng (đơn được duyệt). */
    public int consumeReserved(String balanceId, BigDecimal days) {
        return jdbc.update("""
                UPDATE leave_balances SET pending = pending - ?, used = used + ?, version = version + 1,
                       updated_at = SYSUTCDATETIME()
                WHERE id = ? AND pending >= ?
                """, days, days, balanceId, days);
    }

    /** Hoàn phép của đơn đã duyệt bị hủy. */
    public int restore(String balanceId, BigDecimal days) {
        return jdbc.update("""
                UPDATE leave_balances SET used = used - ?, version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND used >= ?
                """, days, balanceId, days);
    }

    /** Admin điều chỉnh (+/-) theo version đang xem; không cho số còn lại âm. */
    public int adjust(String balanceId, int expectedVersion, BigDecimal delta) {
        return jdbc.update("""
                UPDATE leave_balances SET adjusted = adjusted + ?, version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND entitled + adjusted + ? - used - pending >= 0
                """, delta, balanceId, expectedVersion, delta);
    }

    /* ── Sổ cái ─────────────────────────────────────────────────────────── */

    public void insertLedger(String balanceId, String entryType, BigDecimal days, String leaveRequestId,
                             String reason, String actorId) {
        jdbc.update("""
                INSERT INTO leave_balance_ledger (id, balance_id, entry_type, days, leave_request_id, reason, actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Ids.next("lled"), balanceId, entryType, days, leaveRequestId, reason, actorId);
    }

    public List<LeaveLedgerEntry> findLedger(String balanceId) {
        return jdbc.query("""
                SELECT l.*, u.full_name AS actor_name FROM leave_balance_ledger l
                LEFT JOIN users u ON u.id = l.actor_id
                WHERE l.balance_id = ? ORDER BY l.created_at, l.id
                """, (rs, i) -> new LeaveLedgerEntry(rs.getString("id"), rs.getString("balance_id"),
                rs.getString("entry_type"), rs.getBigDecimal("days"), rs.getString("leave_request_id"),
                rs.getString("reason"), rs.getString("actor_id"), rs.getString("actor_name"),
                ts(rs.getTimestamp("created_at"))), balanceId);
    }

    /** Ngày đang giữ chỗ cho một đơn trên một số dư = reserve − release − consume. */
    public BigDecimal reservedFor(String balanceId, String leaveRequestId) {
        return net(balanceId, leaveRequestId, "reserve", "release", "consume");
    }

    /** Ngày đã trừ cho một đơn trên một số dư = consume − restore. */
    public BigDecimal consumedFor(String balanceId, String leaveRequestId) {
        return net(balanceId, leaveRequestId, "consume", "restore", null);
    }

    private BigDecimal net(String balanceId, String requestId, String plus, String minus1, String minus2) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN entry_type = ? THEN days
                                         WHEN entry_type IN (?, ?) THEN -days ELSE 0 END), 0)
                FROM leave_balance_ledger WHERE balance_id = ? AND leave_request_id = ?
                """, BigDecimal.class, plus, minus1, minus2 == null ? minus1 : minus2, balanceId, requestId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
