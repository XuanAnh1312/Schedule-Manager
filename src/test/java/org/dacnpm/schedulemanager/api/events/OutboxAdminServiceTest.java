package org.dacnpm.schedulemanager.api.events;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.OutboxDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxAdminServiceTest {

    static final CurrentUser EMPLOYEE = new CurrentUser("nv001", false);
    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final String REASON = "Đã sửa dữ liệu người nhận";

    OutboxEventRepository repository;
    OutboxDispatcher dispatcher;
    AuditTrail audit;
    TxCounter txManager;
    OutboxAdminService service;

    /** Quản lý giao dịch giả: chạy callback, đếm commit/rollback, không cần DB. */
    static class TxCounter implements PlatformTransactionManager {
        int commits;
        int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        dispatcher = mock(OutboxDispatcher.class);
        audit = mock(AuditTrail.class);
        txManager = new TxCounter();
        Clock clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new OutboxAdminService(repository, dispatcher, audit, new TransactionTemplate(txManager), clock);
        when(repository.countByStatus()).thenReturn(Map.of("dead", 1L));
    }

    static OutboxEventView event(String id, String status, int attempts) {
        return new OutboxEventView(id, "leave.approved", "leave_request", "lv_1", 2, "admin", "Đơn nghỉ đã duyệt",
                status, attempts, LocalDateTime.of(2026, 11, 12, 1, 0), LocalDateTime.of(2026, 11, 12, 2, 0),
                null, "SQL timeout", 0);
    }

    // ── Quyền ────────────────────────────────────────────────────────────

    @Test
    void employee_cannotListOrRetry() {
        assertThrows(ApiException.class, () -> service.list(EMPLOYEE, "problem", null, null, null, 0, 20));
        assertThrows(ApiException.class, () -> service.summary(EMPLOYEE));
        assertThrows(ApiException.class, () -> service.retry(EMPLOYEE, "evt_1", REASON, true));
        assertThrows(ApiException.class, () -> service.retryAll(EMPLOYEE, "dead", null, REASON));
        verifyNoInteractions(repository, dispatcher, audit);
    }

    // ── Danh sách ────────────────────────────────────────────────────────

    @Test
    void list_defaultsToFailedAndDead() {
        service.list(ADMIN, null, null, null, null, 0, 20);
        ArgumentCaptor<OutboxEventFilter> filter = ArgumentCaptor.forClass(OutboxEventFilter.class);
        verify(repository).find(filter.capture(), eq(0), eq(20));
        assertEquals(List.of("failed", "dead"), filter.getValue().statuses());
    }

    @Test
    void list_allMeansNoStatusFilter_andPagingOffset() {
        service.list(ADMIN, "all", " ", null, null, 2, 10);
        ArgumentCaptor<OutboxEventFilter> filter = ArgumentCaptor.forClass(OutboxEventFilter.class);
        verify(repository).find(filter.capture(), eq(20), eq(10));
        assertEquals(List.of(), filter.getValue().statuses());
        assertEquals(null, filter.getValue().type());
    }

    @Test
    void list_convertsBusinessDatesToUtcRange() {
        // 12/11 theo giờ Việt Nam (UTC+7) = [11/11 17:00 UTC, 12/11 17:00 UTC)
        service.list(ADMIN, "dead", null, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12), 0, 20);
        ArgumentCaptor<OutboxEventFilter> filter = ArgumentCaptor.forClass(OutboxEventFilter.class);
        verify(repository).find(filter.capture(), anyInt(), anyInt());
        assertEquals(LocalDateTime.of(2026, 11, 11, 17, 0), filter.getValue().fromUtc());
        assertEquals(LocalDateTime.of(2026, 11, 12, 17, 0), filter.getValue().toUtc());
    }

    @Test
    void list_validatesInput() {
        assertThrows(ApiException.class, () -> service.list(ADMIN, "problem", null, null, null, -1, 20));
        assertThrows(ApiException.class, () -> service.list(ADMIN, "problem", null, null, null, 0, 101));
        assertThrows(ApiException.class, () -> service.list(ADMIN, "unknown", null, null, null, 0, 20));
        assertThrows(ApiException.class, () -> service.list(ADMIN, "problem", null,
                LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 12), 0, 20));
    }

    // ── Gửi lại một sự kiện ──────────────────────────────────────────────

    @Test
    void retry_deadEvent_resetsAuditsThenDispatchesAfterCommit() {
        when(repository.lockById("evt_1")).thenReturn(event("evt_1", "dead", 8));
        when(repository.resetForRetry("evt_1", "dead")).thenReturn(1);
        when(repository.findById("evt_1")).thenReturn(event("evt_1", "processed", 1));
        when(dispatcher.dispatchOne("evt_1")).thenAnswer(inv -> {
            // Dispatcher chỉ được gọi sau khi giao dịch đặt lại trạng thái đã commit.
            assertEquals(1, txManager.commits);
            return true;
        });

        OutboxEventView result = service.retry(ADMIN, "evt_1", "  " + REASON + "  ", true);

        assertEquals("processed", result.status());
        verify(audit).record(eq("outbox_event_retry"), anyString(), eq("evt_1"), eq("admin"),
                any(), any(), eq(REASON));
        verify(dispatcher).dispatchOne("evt_1");
    }

    @Test
    void retry_withoutDispatchNow_onlyQueues() {
        when(repository.lockById("evt_1")).thenReturn(event("evt_1", "failed", 3));
        when(repository.resetForRetry("evt_1", "failed")).thenReturn(1);
        service.retry(ADMIN, "evt_1", REASON, false);
        verify(dispatcher, never()).dispatchOne(anyString());
    }

    @Test
    void retry_requiresReason() {
        assertThrows(ApiException.class, () -> service.retry(ADMIN, "evt_1", null, true));
        assertThrows(ApiException.class, () -> service.retry(ADMIN, "evt_1", "  ab ", true));
        assertThrows(ApiException.class, () -> service.retry(ADMIN, "evt_1", "x".repeat(501), true));
        verifyNoInteractions(repository, dispatcher);
    }

    @Test
    void retry_unknownEvent_isNotFound_andRollsBack() {
        when(repository.lockById("evt_x")).thenReturn(null);
        ApiException ex = assertThrows(ApiException.class, () -> service.retry(ADMIN, "evt_x", REASON, true));
        assertEquals("NOT_FOUND", ex.getCode());
        assertEquals(1, txManager.rollbacks);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void retry_processedOrPendingEvent_isConflict_noAudit() {
        for (String status : List.of("processed", "pending", "processing")) {
            when(repository.lockById("evt_1")).thenReturn(event("evt_1", status, 1));
            ApiException ex = assertThrows(ApiException.class, () -> service.retry(ADMIN, "evt_1", REASON, true));
            assertEquals("EVENT_NOT_RETRYABLE", ex.getCode());
        }
        verify(repository, never()).resetForRetry(anyString(), anyString());
        verifyNoInteractions(audit, dispatcher);
    }

    @Test
    void retry_statusChangedConcurrently_isConflict_noAudit() {
        when(repository.lockById("evt_1")).thenReturn(event("evt_1", "dead", 8));
        when(repository.resetForRetry("evt_1", "dead")).thenReturn(0);
        ApiException ex = assertThrows(ApiException.class, () -> service.retry(ADMIN, "evt_1", REASON, true));
        assertEquals("EVENT_CHANGED", ex.getCode());
        verifyNoInteractions(audit, dispatcher);
    }

    // ── Gửi lại hàng loạt ────────────────────────────────────────────────

    @Test
    void retryAll_queuesLockedEvents_andWritesOneAudit() {
        when(repository.lockIdsForRetry(List.of("dead"), null, OutboxAdminService.MAX_BULK_RETRY))
                .thenReturn(List.of("evt_1", "evt_2"));
        when(repository.findById("evt_1")).thenReturn(event("evt_1", "dead", 8));
        when(repository.findById("evt_2")).thenReturn(event("evt_2", "dead", 8));
        when(repository.resetForRetry(anyString(), eq("dead"))).thenReturn(1);

        Map<String, Object> result = service.retryAll(ADMIN, "dead", "", REASON);

        assertEquals(2, result.get("queued"));
        verify(audit).record(eq("outbox_event_retry_bulk"), anyString(), anyString(), eq("admin"),
                any(), any(), eq(REASON));
        verify(dispatcher, never()).dispatchOne(anyString());
    }

    @Test
    void retryAll_rejectsNonRetryableStatus() {
        assertThrows(ApiException.class, () -> service.retryAll(ADMIN, "processed", null, REASON));
        assertThrows(ApiException.class, () -> service.retryAll(ADMIN, "all", null, REASON));
        verifyNoInteractions(repository);
    }

    @Test
    void retryAll_nothingToRetry_noAudit() {
        when(repository.lockIdsForRetry(any(), any(), anyInt())).thenReturn(List.of());
        Map<String, Object> result = service.retryAll(ADMIN, null, null, REASON);
        assertEquals(0, result.get("queued"));
        verifyNoInteractions(audit);
    }
}
