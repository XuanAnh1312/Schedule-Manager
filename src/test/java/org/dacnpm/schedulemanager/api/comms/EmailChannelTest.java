package org.dacnpm.schedulemanager.api.comms;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BAO-05: định tuyến email, chống trùng, tùy chọn người dùng, retry/dead và gửi lại có kiểm soát. */
class EmailChannelTest {

    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    EmailRepository repo;

    static MailSettings settings(boolean enabled, String mandatory) {
        return new MailSettings(enabled, "log", "localhost", 1025, "", "", false, "no-reply@x", 3,
                MailSettings.DEFAULT_TYPES, mandatory);
    }

    @BeforeEach
    void setUp() {
        repo = mock(EmailRepository.class);
        when(repo.emailOf("nv001")).thenReturn(Optional.of("an@cty.vn"));
        when(repo.emailOf("nv002")).thenReturn(Optional.empty());
        when(repo.enqueue(anyString(), anyString(), any(), anyString(), anyString(), anyString(), any())).thenReturn(1);
    }

    @Test
    void channelDisabled_queuesNothing() {
        int n = new EmailOutbox(settings(false, ""), repo).enqueue("e1", "leave.approved", "l1", "T", "B", List.of("nv001"));
        assertEquals(0, n);
        verify(repo, never()).enqueue(anyString(), anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void onlyRoutedTypesSendEmail_andMissingAddressIsRecordedAsSkipped() {
        EmailOutbox outbox = new EmailOutbox(settings(true, ""), repo);

        assertEquals(0, outbox.enqueue("e0", "shift_request.created", "r1", "T", "B", List.of("nv001")));
        assertEquals(2, outbox.enqueue("e1", "leave.approved", "l1", "Đã duyệt", "Nội dung", List.of("nv001", "nv002")));

        verify(repo).enqueue(eq("e1"), eq("nv001"), eq("an@cty.vn"), eq("Đã duyệt"), anyString(), eq("pending"), isNull());
        verify(repo).enqueue(eq("e1"), eq("nv002"), isNull(), eq("Đã duyệt"), anyString(), eq("skipped"), anyString());
    }

    @Test
    void optOut_isRespected_exceptForMandatoryTypes() {
        when(repo.emailDisabled("nv001", "leave.approved")).thenReturn(true);
        when(repo.emailDisabled("nv001", "hr.contract_expiring")).thenReturn(true);
        EmailOutbox outbox = new EmailOutbox(settings(true, "hr.contract_expiring"), repo);

        assertEquals(0, outbox.enqueue("e1", "leave.approved", "l1", "T", "B", List.of("nv001")));
        assertEquals(1, outbox.enqueue("e2", "hr.contract_expiring", "c1", "T", "B", List.of("nv001")));
    }

    @Test
    void announcement_sendsEmailOnlyWhenTicked_andMandatoryIgnoresOptOut() {
        when(repo.announcementFlags("ann_plain")).thenReturn(Optional.of(new boolean[] {false, false}));
        when(repo.announcementFlags("ann_mail")).thenReturn(Optional.of(new boolean[] {true, true}));
        when(repo.emailDisabled("nv001", EmailOutbox.ANNOUNCEMENT)).thenReturn(true);
        EmailOutbox outbox = new EmailOutbox(settings(true, ""), repo);

        assertEquals(0, outbox.enqueue("e1", EmailOutbox.ANNOUNCEMENT, "ann_plain", "T", "B", List.of("nv001")));
        assertEquals(1, outbox.enqueue("e2", EmailOutbox.ANNOUNCEMENT, "ann_mail", "T", "B", List.of("nv001")));
    }

    static EmailRepository.EmailRow row(String status) {
        return new EmailRepository.EmailRow("m1", "e1", "leave.approved", "nv001", "Trần Văn An", "an@cty.vn", "S", "B",
                status, 1, null, null, null, LocalDateTime.of(2026, 11, 1, 1, 0));
    }

    @Test
    void dispatcher_marksSent_orFailedWithTheError_andSkipsWhatAnotherInstanceClaimed() {
        when(repo.claim("m1")).thenReturn(1, 1, 0);
        when(repo.find("m1")).thenReturn(Optional.of(row("sending")));
        EmailTransport ok = (to, s, b) -> { };
        EmailTransport broken = (to, s, b) -> { throw new java.io.IOException("SMTP từ chối RCPT TO: 550"); };

        assertEquals("sent", new EmailDispatcher(repo, settings(true, ""), ok, 10).sendOne("m1"));
        verify(repo).markSent("m1");
        assertEquals("failed", new EmailDispatcher(repo, settings(true, ""), broken, 10).sendOne("m1"));
        verify(repo).markFailed("m1", 3, "SMTP từ chối RCPT TO: 550");
        assertEquals("skipped", new EmailDispatcher(repo, settings(true, ""), ok, 10).sendOne("m1"));
    }

    @Test
    void dispatcher_doesNothingWhileTheChannelIsDisabled() {
        new EmailDispatcher(repo, settings(false, ""), (to, s, b) -> { }, 10).poll();
        verify(repo, never()).findDue(anyInt());
    }

    @Test
    void retry_requiresAdminAndReason_andOnlyFailedOrDead() {
        EmailAdminService service = new EmailAdminService(repo, mock(EmailDispatcher.class), settings(true, ""),
                mock(AuditTrail.class));
        when(repo.find("m1")).thenReturn(Optional.of(row("sent")));

        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> service.retry(new CurrentUser("nv001", false), "m1", "Đã sửa SMTP")).getCode());
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class, () -> service.retry(ADMIN, "m1", "x")).getCode());
        assertEquals("EMAIL_NOT_RETRYABLE", assertThrows(ApiException.class,
                () -> service.retry(ADMIN, "m1", "Đã sửa SMTP")).getCode());

        when(repo.find("m2")).thenReturn(Optional.of(row("dead")));
        when(repo.resetForRetry("m2")).thenReturn(1);
        service.retry(ADMIN, "m2", "Đã sửa cấu hình SMTP");
        verify(repo).lock("m2");
        verify(repo).resetForRetry("m2");
    }
}
