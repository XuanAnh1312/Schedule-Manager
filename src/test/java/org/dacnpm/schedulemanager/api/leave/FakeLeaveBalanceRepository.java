package org.dacnpm.schedulemanager.api.leave;

import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sổ phép trong bộ nhớ, mô phỏng đúng điều kiện của các câu UPDATE trong {@link LeaveBalanceRepository} và CHECK
 * {@code remaining >= 0} — để kiểm thử nghiệp vụ không cần SQL Server.
 */
class FakeLeaveBalanceRepository extends LeaveBalanceRepository {

    static final class Row {
        String id, userId, typeId, policyId;
        int year, version;
        BigDecimal entitled, adjusted = BigDecimal.ZERO, used = BigDecimal.ZERO, pending = BigDecimal.ZERO;

        BigDecimal remaining() {
            return entitled.add(adjusted).subtract(used).subtract(pending);
        }
    }

    record Ledger(String balanceId, String type, BigDecimal days, String requestId, String reason, String actor) {}

    final Map<String, Row> balances = new LinkedHashMap<>();
    final List<Ledger> ledger = new ArrayList<>();
    final List<LeavePolicyView> policies = new ArrayList<>();
    final Map<String, String> employeeTypes = new LinkedHashMap<>();
    final List<String> locks = new ArrayList<>();

    FakeLeaveBalanceRepository() {
        super(null);
    }

    void policy(String id, String employeeType, String days, LocalDate from, LocalDate to) {
        policies.add(new LeavePolicyView(id, "leave_annual", "Nghỉ phép năm", employeeType, employeeType,
                new BigDecimal(days), from, to, null, 0));
    }

    @Override public List<LeavePolicyView> findPolicies(String leaveTypeId) {
        return policies.stream().filter(p -> leaveTypeId == null || p.leaveTypeId().equals(leaveTypeId)).toList();
    }

    @Override public Optional<LeavePolicyView> findPolicy(String id) {
        return policies.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    @Override public boolean policyOverlaps(String typeId, String employeeType, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.MAX : to;
        return policies.stream().anyMatch(p -> p.leaveTypeId().equals(typeId)
                && Objects.equals(p.employeeTypeId(), employeeType)
                && !p.effectiveFrom().isAfter(end)
                && !(p.effectiveTo() == null ? LocalDate.MAX : p.effectiveTo()).isBefore(from));
    }

    @Override public void insertPolicy(String id, String typeId, String employeeType, BigDecimal days, LocalDate from,
                                       LocalDate to, String note, String actorId) {
        policies.add(new LeavePolicyView(id, typeId, "Nghỉ phép năm", employeeType, employeeType, days, from, to, note, 0));
    }

    @Override public int endPolicy(String id, int expectedVersion, LocalDate effectiveTo) {
        for (int i = 0; i < policies.size(); i++) {
            LeavePolicyView p = policies.get(i);
            if (p.id().equals(id) && p.version() == expectedVersion && !p.effectiveFrom().isAfter(effectiveTo)
                    && (p.effectiveTo() == null || p.effectiveTo().isAfter(effectiveTo))) {
                policies.set(i, new LeavePolicyView(p.id(), p.leaveTypeId(), p.leaveTypeName(), p.employeeTypeId(),
                        p.employeeTypeName(), p.daysPerYear(), p.effectiveFrom(), effectiveTo, p.note(), p.version() + 1));
                return 1;
            }
        }
        return 0;
    }

    @Override public Optional<String> employeeTypeOf(String userId) {
        return Optional.ofNullable(employeeTypes.get(userId));
    }

    @Override public boolean employeeTypeExists(String id) {
        return employeeTypes.containsValue(id);
    }

    @Override public Optional<LeaveBalanceView> findBalance(String userId, int year, String typeId) {
        return balances.values().stream().filter(r -> r.userId.equals(userId) && r.year == year && r.typeId.equals(typeId))
                .findFirst().map(FakeLeaveBalanceRepository::view);
    }

    @Override public Optional<LeaveBalanceView> findBalanceById(String id) {
        return Optional.ofNullable(balances.get(id)).map(FakeLeaveBalanceRepository::view);
    }

    @Override public List<LeaveBalanceView> findBalances(String userId, Integer year) {
        return balances.values().stream().filter(r -> (userId == null || r.userId.equals(userId))
                && (year == null || r.year == year)).map(FakeLeaveBalanceRepository::view).toList();
    }

    @Override public void lockBalance(String balanceId) {
        locks.add(balanceId);
    }

    @Override public void insertBalance(String id, String userId, int year, String typeId, String policyId,
                                        BigDecimal entitled) {
        if (findBalance(userId, year, typeId).isPresent()) throw new DuplicateKeyException("uq_leave_balances");
        Row r = new Row();
        r.id = id; r.userId = userId; r.year = year; r.typeId = typeId; r.policyId = policyId; r.entitled = entitled;
        balances.put(id, r);
    }

    private int update(String id, java.util.function.Predicate<Row> condition, java.util.function.Consumer<Row> change) {
        Row r = balances.get(id);
        if (r == null || !condition.test(r)) return 0;
        change.accept(r);
        if (r.remaining().signum() < 0 || r.used.signum() < 0 || r.pending.signum() < 0) {
            throw new IllegalStateException("CHECK constraint violated");
        }
        r.version++;
        return 1;
    }

    @Override public int reserve(String id, BigDecimal d) {
        return update(id, r -> r.remaining().compareTo(d) >= 0, r -> r.pending = r.pending.add(d));
    }

    @Override public int release(String id, BigDecimal d) {
        return update(id, r -> r.pending.compareTo(d) >= 0, r -> r.pending = r.pending.subtract(d));
    }

    @Override public int consumeReserved(String id, BigDecimal d) {
        return update(id, r -> r.pending.compareTo(d) >= 0, r -> { r.pending = r.pending.subtract(d); r.used = r.used.add(d); });
    }

    @Override public int restore(String id, BigDecimal d) {
        return update(id, r -> r.used.compareTo(d) >= 0, r -> r.used = r.used.subtract(d));
    }

    @Override public int adjust(String id, int expectedVersion, BigDecimal delta) {
        return update(id, r -> r.version == expectedVersion && r.remaining().add(delta).signum() >= 0,
                r -> r.adjusted = r.adjusted.add(delta));
    }

    @Override public void insertLedger(String balanceId, String type, BigDecimal days, String requestId, String reason,
                                       String actorId) {
        ledger.add(new Ledger(balanceId, type, days, requestId, reason, actorId));
    }

    @Override public List<LeaveLedgerEntry> findLedger(String balanceId) {
        return ledger.stream().filter(l -> l.balanceId().equals(balanceId)).map(l -> new LeaveLedgerEntry(null, balanceId,
                l.type(), l.days(), l.requestId(), l.reason(), l.actor(), null, null)).toList();
    }

    @Override public BigDecimal reservedFor(String balanceId, String requestId) {
        return sum(balanceId, requestId, "reserve").subtract(sum(balanceId, requestId, "release"))
                .subtract(sum(balanceId, requestId, "consume"));
    }

    @Override public BigDecimal consumedFor(String balanceId, String requestId) {
        return sum(balanceId, requestId, "consume").subtract(sum(balanceId, requestId, "restore"));
    }

    private BigDecimal sum(String balanceId, String requestId, String type) {
        return ledger.stream().filter(l -> l.balanceId().equals(balanceId) && Objects.equals(l.requestId(), requestId)
                && l.type().equals(type)).map(Ledger::days).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Tính lại số dư hoàn toàn từ sổ cái — để kiểm tra bất biến "số dư = tổng sổ cái". */
    Map<String, BigDecimal> replay(String balanceId) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (String k : List.of("entitled", "adjusted", "used", "pending")) m.put(k, BigDecimal.ZERO);
        for (Ledger l : ledger) {
            if (!l.balanceId().equals(balanceId)) continue;
            switch (l.type()) {
                case "grant" -> m.merge("entitled", l.days(), BigDecimal::add);
                case "adjust_up" -> m.merge("adjusted", l.days(), BigDecimal::add);
                case "adjust_down" -> m.merge("adjusted", l.days().negate(), BigDecimal::add);
                case "reserve" -> m.merge("pending", l.days(), BigDecimal::add);
                case "release" -> m.merge("pending", l.days().negate(), BigDecimal::add);
                case "consume" -> { m.merge("pending", l.days().negate(), BigDecimal::add); m.merge("used", l.days(), BigDecimal::add); }
                case "restore" -> m.merge("used", l.days().negate(), BigDecimal::add);
                default -> throw new IllegalStateException(l.type());
            }
        }
        return m;
    }

    private static LeaveBalanceView view(Row r) {
        return new LeaveBalanceView(r.id, r.userId, r.userId, r.year, r.typeId, "Nghỉ phép năm", r.policyId, r.entitled,
                r.adjusted, r.used, r.pending, r.remaining(), r.version, null);
    }
}
