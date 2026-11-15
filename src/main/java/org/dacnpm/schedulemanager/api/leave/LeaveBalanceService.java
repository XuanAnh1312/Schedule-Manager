package org.dacnpm.schedulemanager.api.leave;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Sổ phép (BAO-03, CO-01 phần 1): được hưởng / đã dùng / đang chờ / còn lại, theo sổ cái.
 *
 * <h3>Vòng đời số ngày của một đơn (loại nghỉ có {@code tracks_balance})</h3>
 * <pre>
 * gửi đơn ── reserve (pending +) ──► duyệt ── consume (pending −, used +) ──► hủy đơn đã duyệt ── restore (used −)
 *              └─ từ chối / hủy khi chờ ── release (pending −)
 * </pre>
 *
 * <ul>
 *   <li>Mọi bước gọi trong giao dịch của {@link LeaveService}: lỗi số dư → rollback cả đơn, lịch, audit, outbox.</li>
 *   <li>Mỗi bước khóa dòng số dư rồi cập nhật có điều kiện; CHECK {@code remaining >= 0} ở DB là lớp chặn cuối, nên hai
 *       Admin duyệt cùng lúc không trừ hai lần (đơn chỉ chuyển trạng thái được một lần) và không bao giờ âm phép.</li>
 *   <li>Số ngày theo năm dương lịch: đơn vắt qua 31/12 trừ phần mỗi năm vào số dư năm đó. Nửa ngày = 0,5 (chỉ đơn một
 *       ngày). Đếm theo ngày lịch trong khoảng nghỉ — GIẢ ĐỊNH chờ nhóm chốt (có thể đổi sang chỉ ngày làm việc).</li>
 *   <li>Chính sách: theo loại nghỉ × loại nhân viên ({@code users.employee_type_id}); không có chính sách riêng thì dùng
 *       chính sách chung ({@code employee_type_id NULL}); không có gì → từ chối, không tự điền số ngày.
 *       Không chuyển phép sang năm sau, không chia theo ngày vào làm — GIẢ ĐỊNH chờ nhóm chốt.</li>
 * </ul>
 */
@Profile("sqlserver")
@Service
public class LeaveBalanceService {

    static final BigDecimal HALF = new BigDecimal("0.5");
    static final BigDecimal MAX_ADJUST = new BigDecimal("366");

    private final LeaveBalanceRepository repository;
    private final AuditTrail audit;
    private final Clock clock;

    public LeaveBalanceService(LeaveBalanceRepository repository, AuditTrail audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    public record PolicyInput(String leaveTypeId, String employeeTypeId, BigDecimal daysPerYear,
                              LocalDate effectiveFrom, LocalDate effectiveTo, String note) {}

    public record EndPolicyInput(LocalDate effectiveTo, Integer expectedVersion, String reason) {}

    public record AdjustInput(String userId, Integer year, String leaveTypeId, BigDecimal days, String reason,
                              Integer expectedVersion) {}

    /* ── Tính số ngày ───────────────────────────────────────────────────── */

    /** Số ngày theo từng năm của khoảng nghỉ; nửa ngày chỉ áp dụng cho đơn một ngày. */
    static Map<Integer, BigDecimal> daysByYear(LocalDate start, LocalDate end, boolean halfDay) {
        Map<Integer, BigDecimal> result = new TreeMap<>();
        if (halfDay) {
            result.put(start.getYear(), HALF);
            return result;
        }
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            result.merge(d.getYear(), BigDecimal.ONE, BigDecimal::add);
        }
        return result;
    }

    static BigDecimal total(Map<Integer, BigDecimal> byYear) {
        return byYear.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /* ── Gọi từ LeaveService (trong giao dịch của đơn) ──────────────────── */

    /** Giữ chỗ phép cho đơn vừa gửi. Không đủ phép → 422 kèm số còn lại của từng năm. */
    public void reserve(LeaveType type, String userId, String leaveRequestId, LocalDate start, LocalDate end,
                        boolean halfDay, String actorId) {
        if (!type.tracksBalance()) return;
        List<String> shortages = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> e : daysByYear(start, end, halfDay).entrySet()) {
            LeaveBalanceView b = ensureBalance(userId, e.getKey(), type);
            repository.lockBalance(b.id());
            if (repository.reserve(b.id(), e.getValue()) != 1) {
                LeaveBalanceView latest = repository.findBalanceById(b.id()).orElse(b);
                shortages.add("Năm " + e.getKey() + ": cần " + plain(e.getValue()) + " ngày, còn "
                        + plain(latest.remaining()) + " ngày.");
                continue;
            }
            repository.insertLedger(b.id(), "reserve", e.getValue(), leaveRequestId, "Giữ chỗ cho đơn nghỉ", actorId);
        }
        if (!shortages.isEmpty()) {
            // Ném lỗi → rollback mọi giữ chỗ của đơn này ở các năm khác.
            throw ApiException.unprocessable("INSUFFICIENT_BALANCE", "Không đủ số ngày phép còn lại.", shortages);
        }
    }

    /** Trả chỗ đã giữ khi đơn bị từ chối/hủy lúc đang chờ. */
    public void release(LeaveType type, LeaveView leave, String actorId, String reason) {
        if (!type.tracksBalance()) return;
        for (Integer year : daysByYear(leave.startDate(), leave.endDate(), leave.halfDay()).keySet()) {
            Optional<LeaveBalanceView> b = repository.findBalance(leave.userId(), year, type.id());
            if (b.isEmpty()) continue;
            repository.lockBalance(b.get().id());
            BigDecimal reserved = repository.reservedFor(b.get().id(), leave.id());
            if (reserved.signum() <= 0) continue;
            if (repository.release(b.get().id(), reserved) != 1) {
                throw ApiException.conflict("BALANCE_CHANGED", "Số dư phép vừa thay đổi, vui lòng thử lại.");
            }
            repository.insertLedger(b.get().id(), "release", reserved, leave.id(), reason, actorId);
        }
    }

    /**
     * Trừ phép khi đơn được duyệt (bước duyệt cuối). Đơn có giữ chỗ: chuyển giữ chỗ thành đã dùng. Đơn tạo trước V20
     * (chưa giữ chỗ): giữ chỗ bổ sung nếu còn đủ phép rồi trừ; không đủ → 422 để Admin điều chỉnh hoặc từ chối.
     */
    public void consume(LeaveType type, LeaveView leave, String actorId) {
        if (!type.tracksBalance()) return;
        for (Map.Entry<Integer, BigDecimal> e : daysByYear(leave.startDate(), leave.endDate(), leave.halfDay()).entrySet()) {
            LeaveBalanceView b = ensureBalance(leave.userId(), e.getKey(), type);
            repository.lockBalance(b.id());
            BigDecimal need = e.getValue();
            BigDecimal missing = need.subtract(repository.reservedFor(b.id(), leave.id()));
            if (missing.signum() > 0) {
                // Đơn tạo trước V20 (chưa giữ chỗ): giữ chỗ phần thiếu rồi trừ như thường — sổ cái luôn khớp số dư.
                if (repository.reserve(b.id(), missing) != 1) {
                    LeaveBalanceView latest = repository.findBalanceById(b.id()).orElse(b);
                    throw ApiException.unprocessable("INSUFFICIENT_BALANCE", "Không đủ số ngày phép để duyệt đơn.",
                            List.of("Năm " + e.getKey() + ": cần " + plain(missing) + " ngày, còn "
                                    + plain(latest.remaining()) + " ngày."));
                }
                repository.insertLedger(b.id(), "reserve", missing, leave.id(),
                        "Giữ chỗ bổ sung khi duyệt (đơn tạo trước sổ phép)", actorId);
            }
            if (repository.consumeReserved(b.id(), need) != 1) {
                throw ApiException.conflict("BALANCE_CHANGED", "Số dư phép vừa thay đổi, vui lòng thử lại.");
            }
            repository.insertLedger(b.id(), "consume", need, leave.id(), "Đơn nghỉ được duyệt", actorId);
        }
    }

    /** Hoàn phép khi Admin hủy đơn đã duyệt. */
    public void restore(LeaveType type, LeaveView leave, String actorId, String reason) {
        if (!type.tracksBalance()) return;
        for (Integer year : daysByYear(leave.startDate(), leave.endDate(), leave.halfDay()).keySet()) {
            Optional<LeaveBalanceView> b = repository.findBalance(leave.userId(), year, type.id());
            if (b.isEmpty()) continue;
            repository.lockBalance(b.get().id());
            BigDecimal consumed = repository.consumedFor(b.get().id(), leave.id());
            if (consumed.signum() <= 0) continue;
            if (repository.restore(b.get().id(), consumed) != 1) {
                throw ApiException.conflict("BALANCE_CHANGED", "Số dư phép vừa thay đổi, vui lòng thử lại.");
            }
            repository.insertLedger(b.get().id(), "restore", consumed, leave.id(), reason, actorId);
        }
    }

    /* ── API số dư ──────────────────────────────────────────────────────── */

    /** Số dư của tôi trong năm (mặc định năm hiện tại). Loại nghỉ chưa có số dư hiện theo chính sách, chưa ghi DB. */
    public List<LeaveBalanceView> myBalances(CurrentUser me, Integer year, List<LeaveType> types) {
        return balancesOf(me.id(), year == null ? LocalDate.now(clock).getYear() : year, types);
    }

    /** Admin: số dư theo nhân viên/năm. */
    public List<LeaveBalanceView> balances(CurrentUser me, String userId, Integer year, List<LeaveType> types) {
        me.requireAdmin();
        if (userId != null && !userId.isBlank()) {
            return balancesOf(userId.trim(), year == null ? LocalDate.now(clock).getYear() : year, types);
        }
        return repository.findBalances(null, year);
    }

    private List<LeaveBalanceView> balancesOf(String userId, int year, List<LeaveType> types) {
        List<LeaveBalanceView> result = new ArrayList<>();
        for (LeaveType t : types) {
            if (!t.tracksBalance()) continue;
            Optional<LeaveBalanceView> stored = repository.findBalance(userId, year, t.id());
            if (stored.isPresent()) {
                result.add(stored.get());
                continue;
            }
            Optional<LeavePolicyView> policy = policyFor(userId, year, t.id());
            BigDecimal entitled = policy.map(LeavePolicyView::daysPerYear).orElse(null);
            result.add(new LeaveBalanceView(null, userId, null, year, t.id(), t.name(),
                    policy.map(LeavePolicyView::id).orElse(null), entitled, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, entitled, 0, null));
        }
        return result;
    }

    public List<LeaveLedgerEntry> ledger(CurrentUser me, String balanceId) {
        LeaveBalanceView b = repository.findBalanceById(balanceId).orElseThrow(() -> ApiException.notFound("số dư phép"));
        if (!me.admin() && !b.userId().equals(me.id())) throw ApiException.notFound("số dư phép");
        return repository.findLedger(balanceId);
    }

    /** Admin điều chỉnh số dư (+/-) có lý do, theo version đang xem; ghi sổ cái và audit. */
    @Transactional
    public LeaveBalanceView adjust(CurrentUser me, AdjustInput in, LeaveType type) {
        me.requireAdmin();
        if (!type.tracksBalance()) {
            throw ApiException.badRequest("NOT_TRACKED", "Loại nghỉ này không theo dõi số dư.");
        }
        if (in.userId() == null || in.userId().isBlank()) {
            throw ApiException.badRequest("REQUIRED", "Nhân viên là bắt buộc.");
        }
        BigDecimal delta = in.days();
        if (delta == null || delta.signum() == 0 || delta.abs().compareTo(MAX_ADJUST) > 0
                || delta.remainder(HALF).signum() != 0) {
            throw ApiException.badRequest("INVALID_DAYS", "Số ngày điều chỉnh phải khác 0, bội số của 0,5 và tối đa 366.");
        }
        String reason = LeaveService.requireText(in.reason(), "Lý do điều chỉnh");
        int year = in.year() == null ? LocalDate.now(clock).getYear() : in.year();
        LeaveBalanceView before = ensureBalance(in.userId().trim(), year, type);
        repository.lockBalance(before.id());
        LeaveBalanceView locked = repository.findBalanceById(before.id()).orElseThrow();
        int expected = in.expectedVersion() == null ? locked.version() : in.expectedVersion();
        if (repository.adjust(locked.id(), expected, delta) != 1) {
            if (expected != locked.version()) {
                throw ApiException.conflict("STALE_VERSION", "Số dư vừa thay đổi, vui lòng tải lại.");
            }
            throw ApiException.unprocessable("NEGATIVE_BALANCE", "Điều chỉnh làm số ngày còn lại âm.",
                    List.of("Còn lại hiện tại: " + plain(locked.remaining()) + " ngày."));
        }
        repository.insertLedger(locked.id(), delta.signum() > 0 ? "adjust_up" : "adjust_down", delta.abs(), null,
                reason, me.id());
        LeaveBalanceView after = repository.findBalanceById(locked.id()).orElseThrow();
        audit.record("leave_balance_adjust", "Điều chỉnh số dư phép",
                after.userName() + " — " + after.leaveTypeName() + " " + year + ": " + (delta.signum() > 0 ? "+" : "")
                        + plain(delta) + " ngày", me.id(), snapshot(locked), snapshot(after), reason);
        return after;
    }

    /* ── Chính sách ─────────────────────────────────────────────────────── */

    public List<LeavePolicyView> policies(CurrentUser me, String leaveTypeId) {
        me.requireAdmin();
        return repository.findPolicies(leaveTypeId == null || leaveTypeId.isBlank() ? null : leaveTypeId);
    }

    @Transactional
    public LeavePolicyView createPolicy(CurrentUser me, PolicyInput in, LeaveType type) {
        me.requireAdmin();
        if (!type.tracksBalance()) {
            throw ApiException.badRequest("NOT_TRACKED", "Loại nghỉ này không theo dõi số dư, không cần chính sách.");
        }
        BigDecimal days = in.daysPerYear();
        if (days == null || days.signum() < 0 || days.compareTo(MAX_ADJUST) > 0 || days.remainder(HALF).signum() != 0) {
            throw ApiException.badRequest("INVALID_DAYS", "Số ngày/năm từ 0 đến 366, bội số của 0,5.");
        }
        if (in.effectiveFrom() == null) throw ApiException.badRequest("REQUIRED", "Ngày bắt đầu hiệu lực là bắt buộc.");
        if (in.effectiveTo() != null && in.effectiveTo().isBefore(in.effectiveFrom())) {
            throw ApiException.badRequest("INVALID_RANGE", "Ngày kết thúc phải sau ngày bắt đầu hiệu lực.");
        }
        String employeeType = in.employeeTypeId() == null || in.employeeTypeId().isBlank() ? null : in.employeeTypeId();
        if (employeeType != null && !repository.employeeTypeExists(employeeType)) {
            throw ApiException.badRequest("INVALID_EMPLOYEE_TYPE", "Loại nhân viên không tồn tại.");
        }
        if (repository.policyOverlaps(type.id(), employeeType, in.effectiveFrom(), in.effectiveTo())) {
            throw ApiException.conflict("POLICY_OVERLAP",
                    "Đã có chính sách cùng loại nghỉ và loại nhân viên trùng thời gian hiệu lực. Hãy kết thúc chính sách cũ trước.");
        }
        String id = Ids.next("lpol");
        String note = LeaveService.optionalText(in.note());
        repository.insertPolicy(id, type.id(), employeeType, days, in.effectiveFrom(), in.effectiveTo(), note, me.id());
        LeavePolicyView view = repository.findPolicy(id).orElseThrow();
        audit.record("leave_policy_create", "Tạo chính sách phép", describe(view), me.id(), null, view, note);
        return view;
    }

    /** Kết thúc hiệu lực chính sách (không xóa). Số dư đã cấp giữ nguyên căn cứ. */
    @Transactional
    public LeavePolicyView endPolicy(CurrentUser me, String id, EndPolicyInput in) {
        me.requireAdmin();
        LeavePolicyView current = repository.findPolicy(id).orElseThrow(() -> ApiException.notFound("chính sách phép"));
        if (in.effectiveTo() == null) throw ApiException.badRequest("REQUIRED", "Ngày kết thúc hiệu lực là bắt buộc.");
        String reason = LeaveService.requireText(in.reason(), "Lý do");
        int expected = in.expectedVersion() == null ? current.version() : in.expectedVersion();
        if (repository.endPolicy(id, expected, in.effectiveTo()) != 1) {
            throw ApiException.conflict("INVALID_END_DATE",
                    "Không kết thúc được: chính sách đã đổi, đã kết thúc trước ngày này hoặc ngày kết thúc trước ngày bắt đầu.");
        }
        LeavePolicyView after = repository.findPolicy(id).orElseThrow();
        audit.record("leave_policy_end", "Kết thúc chính sách phép", describe(after), me.id(), current, after, reason);
        return after;
    }

    /* ── Hỗ trợ ─────────────────────────────────────────────────────────── */

    /**
     * Lấy hoặc tạo số dư năm theo chính sách. Chính sách áp dụng: hiệu lực ngày 01/01 của năm; nếu không có, chính sách
     * bắt đầu muộn nhất trong năm. Tạo đồng thời → UNIQUE chặn, đọc lại bản đã có.
     */
    LeaveBalanceView ensureBalance(String userId, int year, LeaveType type) {
        Optional<LeaveBalanceView> existing = repository.findBalance(userId, year, type.id());
        if (existing.isPresent()) return existing.get();
        LeavePolicyView policy = policyFor(userId, year, type.id()).orElseThrow(() -> ApiException.unprocessable(
                "POLICY_MISSING", "Chưa có chính sách " + type.name().toLowerCase() + " áp dụng cho nhân viên này năm "
                        + year + ". Admin cần cấu hình chính sách phép trước.", List.of()));
        String id = Ids.next("lbal");
        try {
            repository.insertBalance(id, userId, year, type.id(), policy.id(), policy.daysPerYear());
            if (policy.daysPerYear().signum() > 0) {
                repository.insertLedger(id, "grant", policy.daysPerYear(), null,
                        "Cấp phép năm " + year + " theo chính sách " + policy.id(), null);
            }
        } catch (DuplicateKeyException e) {
            return repository.findBalance(userId, year, type.id()).orElseThrow();
        }
        return repository.findBalanceById(id).orElseThrow();
    }

    Optional<LeavePolicyView> policyFor(String userId, int year, String leaveTypeId) {
        String employeeType = repository.employeeTypeOf(userId).orElse(null);
        List<LeavePolicyView> all = repository.findPolicies(leaveTypeId);
        LocalDate jan1 = LocalDate.of(year, 1, 1);
        LocalDate dec31 = LocalDate.of(year, 12, 31);
        for (String scope : employeeType == null ? new String[] {null} : new String[] {employeeType, null}) {
            List<LeavePolicyView> candidates = all.stream()
                    .filter(p -> java.util.Objects.equals(p.employeeTypeId(), scope)).toList();
            Optional<LeavePolicyView> atStart = candidates.stream().filter(p -> p.coversDate(jan1)).findFirst();
            if (atStart.isPresent()) return atStart;
            Optional<LeavePolicyView> inYear = candidates.stream()
                    .filter(p -> !p.effectiveFrom().isAfter(dec31) && !p.effectiveFrom().isBefore(jan1))
                    .max(java.util.Comparator.comparing(LeavePolicyView::effectiveFrom));
            if (inYear.isPresent()) return inYear;
        }
        return Optional.empty();
    }

    private static Map<String, Object> snapshot(LeaveBalanceView b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("year", b.year());
        m.put("entitled", b.entitled());
        m.put("adjusted", b.adjusted());
        m.put("used", b.used());
        m.put("pending", b.pending());
        m.put("remaining", b.remaining());
        m.put("version", b.version());
        return m;
    }

    private static String describe(LeavePolicyView p) {
        return p.leaveTypeName() + " · " + (p.employeeTypeName() == null ? "mọi loại nhân viên" : p.employeeTypeName())
                + " · " + plain(p.daysPerYear()) + " ngày/năm từ " + p.effectiveFrom()
                + (p.effectiveTo() == null ? "" : " đến " + p.effectiveTo());
    }

    static String plain(BigDecimal v) {
        return v == null ? "?" : v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
