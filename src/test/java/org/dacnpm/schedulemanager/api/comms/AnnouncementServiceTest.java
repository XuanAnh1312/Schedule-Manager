package org.dacnpm.schedulemanager.api.comms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BAO-05: thông báo chung theo phạm vi tổ chức và tùy chọn nhận email. */
class AnnouncementServiceTest {

    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final CurrentUser AN = new CurrentUser("nv001", false);

    AnnouncementRepository repo;
    DomainEventPublisher events;
    AnnouncementService service;

    @BeforeEach
    void setUp() {
        repo = mock(AnnouncementRepository.class);
        events = mock(DomainEventPublisher.class);
        service = new AnnouncementService(repo, events, mock(AuditTrail.class), new ObjectMapper());
        when(repo.find(anyString())).thenReturn(Optional.of(new AnnouncementRepository.Announcement("ann1", "T", "B",
                "branch", "[]", null, false, false, 2, "admin", "Admin", null, 0)));
    }

    @Test
    void send_toABranch_publishesOneEventForEveryRecipient_inTheSameTransaction() {
        when(repo.recipients("branch", List.of("br_hcm"))).thenReturn(List.of("nv001", "nv002"));

        service.send(ADMIN, new AnnouncementService.SendInput("Lịch nghỉ Tết", "Nội dung", "branch",
                List.of("br_hcm", "br_hcm", " "), null, true, false));

        verify(repo).insert(anyString(), eq("Lịch nghỉ Tết"), eq("Nội dung"), eq("branch"), eq("[\"br_hcm\"]"), any(),
                eq(true), eq(false), eq(2), eq("admin"));
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals("announcement.published", event.getValue().type());
        assertEquals(List.of("nv001", "nv002"), event.getValue().recipients());
    }

    @Test
    void longBody_isTruncatedForTheInAppNotification_butStoredInFull() {
        when(repo.recipients("company", List.of())).thenReturn(List.of("nv001"));
        String body = "x".repeat(1500);

        service.send(ADMIN, new AnnouncementService.SendInput("T", body, "company", null, null, false, false));

        verify(repo).insert(anyString(), eq("T"), eq(body), eq("company"), eq("[]"), any(), anyBoolean(), anyBoolean(),
                anyInt(), anyString());
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals(1000, event.getValue().body().length());
    }

    @Test
    void invalidScope_emptyScope_orNoRecipients_areRejected_andNothingIsPublished() {
        assertEquals("INVALID_SCOPE", assertThrows(ApiException.class, () -> service.send(ADMIN,
                new AnnouncementService.SendInput("T", "B", "city", null, null, false, false))).getCode());
        assertEquals("SCOPE_REQUIRED", assertThrows(ApiException.class, () -> service.send(ADMIN,
                new AnnouncementService.SendInput("T", "B", "department", List.of(), null, false, false))).getCode());
        when(repo.recipients("group", List.of("g1"))).thenReturn(List.of());
        assertEquals("NO_RECIPIENTS", assertThrows(ApiException.class, () -> service.send(ADMIN,
                new AnnouncementService.SendInput("T", "B", "group", List.of("g1"), null, false, false))).getCode());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> service.send(AN,
                new AnnouncementService.SendInput("T", "B", "company", null, null, false, false))).getCode());
        verify(events, never()).publish(any());
    }

    @Test
    void readHistory_countsReaders() {
        when(repo.reads("ann1")).thenReturn(List.of(Map.of("userId", "nv001", "isRead", true),
                Map.of("userId", "nv002", "isRead", false)));
        Map<String, Object> result = service.reads(ADMIN, "ann1");
        assertEquals(1L, result.get("readCount"));
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> service.reads(AN, "ann1")).getCode());
    }

    @Test
    void preferences_mandatoryTypeCannotBeTurnedOff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationPreferenceService prefs = new NotificationPreferenceService(jdbc,
                new MailSettings(true, "log", "h", 1, "", "", false, "f", 3, MailSettings.DEFAULT_TYPES, "hr.contract_expiring"));

        ApiException ex = assertThrows(ApiException.class, () -> prefs.update(AN,
                List.of(new NotificationPreferenceService.UpdateInput("hr.contract_expiring", false))));
        assertEquals("MANDATORY_TYPE", ex.getCode());
        assertEquals("INVALID_TYPE", assertThrows(ApiException.class, () -> prefs.update(AN,
                List.of(new NotificationPreferenceService.UpdateInput("bogus", false)))).getCode());

        @SuppressWarnings("unchecked")
        List<NotificationPreferenceService.Preference> items =
                (List<NotificationPreferenceService.Preference>) prefs.mine(AN).get("items");
        NotificationPreferenceService.Preference contract = items.stream()
                .filter(p -> p.type().equals("hr.contract_expiring")).findFirst().orElseThrow();
        assertTrue(contract.mandatory());
        assertTrue(contract.emailEnabled());
        assertFalse(items.stream().anyMatch(p -> p.type().equals("bogus")));
    }
}
