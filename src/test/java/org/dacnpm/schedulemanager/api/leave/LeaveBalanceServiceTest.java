package org.dacnpm.schedulemanager.api.leave;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BAO-03: sổ phép. Dùng sổ phép trong bộ nhớ mô phỏng điều kiện SQL; LeaveRepository được mock.
 * Đồng hồ: 01/11/2026 08:00 giờ VN.
 */
class LeaveBalanceServiceTest {

    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final LeaveType ANNUAL = new LeaveType("leave_annual", "Nghỉ phép năm", null, true, false, 0, true, true);
    static final LeaveType UNPAID = new LeaveType("leave_unpaid", "Nghỉ không lương", null, false, false, 0, true, false);
    static final LocalDate JAN1 = LocalDate.of(2026, 1, 1);

    FakeLeaveBalanceRepository ledger;
    LeaveBalanceService balances;
    LeaveRepository leaves;
    LeaveService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        ledger = new FakeLeaveBalanceRepository();
        ledger.policy("pol_all", null, "12", JAN1, null);
        balances = new LeaveBalanceService(ledger, mock(AuditTrail.class), clock);
        leaves = mock(LeaveRepository.class);
        AssignmentMutationService schedule = mock(AssignmentMutationService.class);
        when(schedule.findConfirmedAssignments(anyString(), any(), any())).thenReturn(List.of());
        service = new LeaveService(leaves, schedule, mock(DomainEventPublisher.class), mock(AuditTrail.class), clock,
                balances);
        when(leaves.findType("leave_annual")).thenReturn(Optional.of(ANNUAL));
        when(leaves.findType("leave_unpaid")).thenReturn(Optional.of(UNPAID));
        when(leaves.overlapsActive(anyString(), any(), any())).thenReturn(false);
    }

    private LeaveView leave(String id, String status, LocalDate start, LocalDate end, boolean half, int version) {
        return new LeaveView(id, "nv001", "Trần Văn An", "leave_annual", "Nghỉ phép năm", start, end, "Về quê", null,
                status, null, null, null, null, 0, version, LocalDateTime.of(2026, 11, 1, 1, 0), half, null);
    }

    private LeaveBalanceView balance2026() {
        return ledger.findBalance("nv001", 2026, "leave_annual").orElseThrow();
    }

    private void assertLedgerMatchesBalance() {
        for (String id : ledger.balances.keySet()) {
            Map<String, BigDecimal> replay = ledger.replay(id);
            LeaveBalanceView b = ledger.findBalanceById(id).orElseThrow();
            assertEquals(0, replay.get("entitled").compareTo(b.entitled()), "entitled");
            assertEquals(0, replay.get("adjusted").compareTo(b.adjusted()), "adjusted");
            assertEquals(0, replay.get("used").compareTo(b.used()), "used");
            assertEquals(0, replay.get("pending").compareTo(b.pending()), "pending");
        }
    }

    /* ── Tính ngày ──────────────────────────────────────────────────── */

    @Test
    void daysAreSplitPerCalendarYear_andHalfDayCountsHalf() {
        assertEquals(Map.of(2026, new BigDecimal("2"), 2027, new BigDecimal("2")),
                LeaveBalanceService.daysByYear(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2), false));
        assertEquals(Map.of(2026, new BigDecimal("0.5")),
                LeaveBalanceService.daysByYear(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5), true));
    }

    /* ── Vòng đời đơn ───────────────────────────────────────────────── */

    @Test
    void submit_reserves_approve_consumes_andTheLedgerAlwaysMatchesTheBalance() {
        LocalDate d5 = LocalDate.of(2026, 11, 5), d7 = LocalDate.of(2026, 11, 7);
        when(leaves.findById(anyString())).thenReturn(Optional.of(leave("leave1", "pending", d5, d7, false, 0)));

        service.create(AN, new LeaveService.CreateLeave("leave_annual", d5, d7, "Về quê", null));
        assertEquals(0, new BigDecimal("3").compareTo(balance2026().pending()));
        assertEquals(0, new BigDecimal("9").compareTo(balance2026().remaining()));

        LeaveView pending = leave("leave_x", "pending", d5, d7, false, 0);
        String requestId = ledger.ledger.stream().filter(l -> l.type().equals("reserve")).findFirst().orElseThrow().requestId();
        pending = new LeaveView(requestId, pending.userId(), pending.userName(), pending.leaveTypeId(), pending.leaveTypeName(),
                d5, d7, pending.reason(), null, "pending", null, null, null, null, 0, 0, pending.createdAt(), false, null);
        when(leaves.findById(requestId)).thenReturn(Optional.of(pending), Optional.of(pending));
        when(leaves.decide(eq(requestId), eq(0), eq("approved"), any(), any())).thenReturn(1);

        service.approve(ADMIN, requestId, 0, null);

        LeaveBalanceView b = balance2026();
        assertEquals(0, BigDecimal.ZERO.compareTo(b.pending()));
        assertEquals(0, new BigDecimal("3").compareTo(b.used()));
        assertEquals(0, new BigDecimal("9").compareTo(b.remaining()));
        assertLedgerMatchesBalance();
    }

    @Test
    void notEnoughDays_isRejectedWithTheRemainingAmount_andNothingIsReserved() {
        LocalDate from = LocalDate.of(2026, 11, 10), to = LocalDate.of(2026, 11, 22); // 13 ngày > 12

        ApiException ex = assertThrows(ApiException.class, () -> service.create(AN,
                new LeaveService.CreateLeave("leave_annual", from, to, "Du lịch", null)));

        assertEquals("INSUFFICIENT_BALANCE", ex.getCode());
        assertEquals(List.of("Năm 2026: cần 13 ngày, còn 12 ngày."), ex.getDetails());
        // Trong thực tế giao dịch rollback cả đơn; ở đây chỉ kiểm tra không có giữ chỗ nào được ghi.
        assertTrue(ledger.ledger.stream().noneMatch(l -> l.type().equals("reserve")));
    }

    @Test
    void reject_and_cancel_releaseTheReservation() {
        LocalDate d5 = LocalDate.of(2026, 11, 5);
        balances.reserve(ANNUAL, "nv001", "leave1", d5, d5, false, "nv001");
        balances.reserve(ANNUAL, "nv001", "leave2", d5.plusDays(2), d5.plusDays(2), true, "nv001");
        assertEquals(0, new BigDecimal("1.5").compareTo(balance2026().pending()));

        balances.release(ANNUAL, leave("leave1", "pending", d5, d5, false, 0), "admin", "Từ chối");
        balances.release(ANNUAL, leave("leave2", "pending", d5.plusDays(2), d5.plusDays(2), true, 0), "nv001", "Hủy");
        // Trả lần hai không trả thêm (đã hết giữ chỗ).
        balances.release(ANNUAL, leave("leave1", "pending", d5, d5, false, 0), "admin", "Lặp");

        assertEquals(0, BigDecimal.ZERO.compareTo(balance2026().pending()));
        assertEquals(0, new BigDecimal("12").compareTo(balance2026().remaining()));
        assertLedgerMatchesBalance();
    }

    @Test
    void doubleApproval_onlyConsumesOnce_becauseTheRequestTransitionsOnce() {
        LocalDate d5 = LocalDate.of(2026, 11, 5);
        balances.reserve(ANNUAL, "nv001", "leave1", d5, d5, false, "nv001");
        LeaveView pending = leave("leave1", "pending", d5, d5, false, 0);
        when(leaves.findById("leave1")).thenReturn(Optional.of(pending), Optional.of(pending),
                Optional.of(pending), Optional.of(leave("leave1", "approved", d5, d5, false, 1)));
        // Admin 1 thắng, Admin 2 thấy version đã đổi (UPDATE có điều kiện trả 0) → đơn đã duyệt, không trừ lần 2.
        when(leaves.decide(eq("leave1"), anyInt(), eq("approved"), any(), any())).thenReturn(1, 0);

        service.approve(ADMIN, "leave1", 0, null);
        service.approve(new CurrentUser("admin2", true), "leave1", 0, null);

        assertEquals(0, BigDecimal.ONE.compareTo(balance2026().used()));
        assertEquals(1, ledger.ledger.stream().filter(l -> l.type().equals("consume")).count());
        assertLedgerMatchesBalance();
    }

    @Test
    void approvingALegacyRequestWithoutReservation_reservesThenConsumes() {
        LocalDate d5 = LocalDate.of(2026, 11, 5);
        balances.consume(ANNUAL, leave("legacy", "pending", d5, d5.plusDays(1), false, 0), "admin");

        assertEquals(0, new BigDecimal("2").compareTo(balance2026().used()));
        assertLedgerMatchesBalance();
    }

    @Test
    void revoke_restoresUsedDays_onlyBeforeTheLeaveStarts() {
        LocalDate d5 = LocalDate.of(2026, 11, 5);
        balances.reserve(ANNUAL, "nv001", "leave1", d5, d5, false, "nv001");
        balances.consume(ANNUAL, leave("leave1", "pending", d5, d5, false, 0), "admin");
        when(leaves.findById("leave1")).thenReturn(Optional.of(leave("leave1", "approved", d5, d5, false, 1)),
                Optional.of(leave("leave1", "cancelled", d5, d5, false, 2)));
        when(leaves.revoke(eq("leave1"), eq(1), eq("admin"), anyString())).thenReturn(1);

        service.revoke(ADMIN, "leave1", 1, "Công ty cần người");

        assertEquals(0, BigDecimal.ZERO.compareTo(balance2026().used()));
        assertLedgerMatchesBalance();

        when(leaves.findById("past")).thenReturn(Optional.of(leave("past", "approved", LocalDate.of(2026, 10, 30),
                LocalDate.of(2026, 10, 30), false, 1)));
        assertEquals("LEAVE_STARTED", assertThrows(ApiException.class,
                () -> service.revoke(ADMIN, "past", 1, "x")).getCode());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> service.revoke(AN, "leave1", 1, "x")).getCode());
    }

    @Test
    void untrackedLeaveType_neverTouchesTheBalance() {
        LocalDate d5 = LocalDate.of(2026, 11, 5);
        balances.reserve(UNPAID, "nv001", "leave1", d5, d5.plusDays(20), false, "nv001");
        assertTrue(ledger.balances.isEmpty());
    }

    /* ── Chính sách ─────────────────────────────────────────────────── */

    @Test
    void partTimePolicy_isUsedWhenConfigured_otherwiseTheGeneralPolicyApplies_neverZeroByDefault() {
        ledger.employeeTypes.put("nv001", "et_part_time");
        ledger.employeeTypes.put("nv002", "et_full_time");
        ledger.policy("pol_pt", "et_part_time", "6", JAN1, null);

        assertEquals("pol_pt", balances.policyFor("nv001", 2026, "leave_annual").orElseThrow().id());
        assertEquals("pol_all", balances.policyFor("nv002", 2026, "leave_annual").orElseThrow().id());
    }

    @Test
    void noPolicyAtAll_meansNoBalanceIsInvented() {
        ledger.policies.clear();
        LocalDate d5 = LocalDate.of(2026, 11, 5);

        ApiException ex = assertThrows(ApiException.class,
                () -> balances.reserve(ANNUAL, "nv001", "leave1", d5, d5, false, "nv001"));

        assertEquals("POLICY_MISSING", ex.getCode());
        List<LeaveBalanceView> mine = balances.myBalances(AN, 2026, List.of(ANNUAL, UNPAID));
        assertEquals(1, mine.size());
        assertNull(mine.get(0).entitled());
    }

    @Test
    void overlappingPolicy_isRejected_andEndingThenCreatingWorks() {
        assertEquals("POLICY_OVERLAP", assertThrows(ApiException.class, () -> balances.createPolicy(ADMIN,
                new LeaveBalanceService.PolicyInput("leave_annual", null, new BigDecimal("14"),
                        LocalDate.of(2027, 1, 1), null, null), ANNUAL)).getCode());

        balances.endPolicy(ADMIN, "pol_all", new LeaveBalanceService.EndPolicyInput(LocalDate.of(2026, 12, 31), 0,
                "Tăng phép từ 2027"));
        LeavePolicyView created = balances.createPolicy(ADMIN, new LeaveBalanceService.PolicyInput("leave_annual",
                null, new BigDecimal("14"), LocalDate.of(2027, 1, 1), null, "Phép mới"), ANNUAL);

        assertEquals(created.id(), balances.policyFor("nv001", 2027, "leave_annual").orElseThrow().id());
        assertEquals("pol_all", balances.policyFor("nv001", 2026, "leave_annual").orElseThrow().id());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> balances.createPolicy(AN,
                new LeaveBalanceService.PolicyInput("leave_annual", null, BigDecimal.ONE, JAN1, null, null), ANNUAL)).getCode());
    }

    /* ── Điều chỉnh ─────────────────────────────────────────────────── */

    @Test
    void adjust_needsReason_staleVersionConflicts_andCannotGoNegative() {
        LocalDate d5 = LocalDate.of(2026, 11, 5);
        balances.reserve(ANNUAL, "nv001", "leave1", d5, d5.plusDays(9), false, "nv001"); // còn 2

        assertEquals("REQUIRED", assertThrows(ApiException.class, () -> balances.adjust(ADMIN,
                new LeaveBalanceService.AdjustInput("nv001", 2026, "leave_annual", BigDecimal.ONE, " ", null), ANNUAL)).getCode());
        assertEquals("NEGATIVE_BALANCE", assertThrows(ApiException.class, () -> balances.adjust(ADMIN,
                new LeaveBalanceService.AdjustInput("nv001", 2026, "leave_annual", new BigDecimal("-3"), "Trừ", null),
                ANNUAL)).getCode());
        assertEquals("STALE_VERSION", assertThrows(ApiException.class, () -> balances.adjust(ADMIN,
                new LeaveBalanceService.AdjustInput("nv001", 2026, "leave_annual", BigDecimal.ONE, "Thưởng", 0),
                ANNUAL)).getCode());

        LeaveBalanceView after = balances.adjust(ADMIN, new LeaveBalanceService.AdjustInput("nv001", 2026,
                "leave_annual", new BigDecimal("1.5"), "Thưởng thâm niên", balance2026().version()), ANNUAL);

        assertEquals(0, new BigDecimal("3.5").compareTo(after.remaining()));
        assertLedgerMatchesBalance();
        assertEquals("INVALID_DAYS", assertThrows(ApiException.class, () -> balances.adjust(ADMIN,
                new LeaveBalanceService.AdjustInput("nv001", 2026, "leave_annual", new BigDecimal("0.3"), "x", null),
                ANNUAL)).getCode());
    }

    @Test
    void ledgerOfAnotherEmployee_isHidden() {
        LocalDate d5 = LocalDate.of(2026, 11, 5);
        balances.reserve(ANNUAL, "nv001", "leave1", d5, d5, false, "nv001");
        String id = balance2026().id();

        assertEquals(2, balances.ledger(AN, id).size()); // grant + reserve
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> balances.ledger(new CurrentUser("nv002", false), id)).getCode());
    }

    @Test
    void halfDayRequest_mustBeASingleDay() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(AN, new LeaveService.CreateLeave(
                "leave_annual", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), "Khám", null, true)));
        assertEquals("INVALID_HALF_DAY", ex.getCode());
    }
}
