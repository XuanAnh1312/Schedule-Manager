package org.dacnpm.schedulemanager.api.notifications;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final CurrentUser ADMIN = new CurrentUser("admin", true);

    NotificationRepository repository;
    NotificationSettingsRepository settings;
    NotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        settings = mock(NotificationSettingsRepository.class);
        service = new NotificationService(repository, settings, mock(AuditTrail.class));
        when(settings.get()).thenReturn(new NotificationSettings(List.of(), false));
    }

    @Test
    void markRead_ofSomeoneElsesNotification_isNotFound() {
        when(repository.markRead("ntf_x", "nv001")).thenReturn(0);
        when(repository.existsForRecipient("ntf_x", "nv001")).thenReturn(false);
        ApiException ex = assertThrows(ApiException.class, () -> service.markRead(AN, "ntf_x"));
        assertEquals("NOT_FOUND", ex.getCode());
    }

    @Test
    void markRead_alreadyRead_isIdempotent() {
        when(repository.markRead("ntf_1", "nv001")).thenReturn(0);
        when(repository.existsForRecipient("ntf_1", "nv001")).thenReturn(true);
        service.markRead(AN, "ntf_1"); // không ném lỗi
    }

    @Test
    void list_validatesPaging() {
        assertThrows(ApiException.class, () -> service.list(AN, false, -1, 20));
        assertThrows(ApiException.class, () -> service.list(AN, false, 0, 101));
    }

    @Test
    void settings_onlyAdmin_andValidated() {
        assertThrows(ApiException.class, () -> service.updateSettings(AN, List.of(24)));
        assertThrows(ApiException.class, () -> service.updateSettings(ADMIN, List.of(0)));
        assertThrows(ApiException.class, () -> service.updateSettings(ADMIN, List.of(1000)));
        assertThrows(ApiException.class, () -> service.updateSettings(ADMIN, Arrays.asList(24, null)));
        verify(settings, never()).saveDeadlineReminderHours(anyList(), any());
    }

    @Test
    void settings_areSortedDescending_andDeduplicated() {
        service.updateSettings(ADMIN, List.of(24, 48, 24));
        verify(settings).saveDeadlineReminderHours(List.of(48, 24), "admin");
    }

    @Test
    void unconfiguredSettings_areReportedAsNotConfigured() {
        assertFalse(service.getSettings(ADMIN).configured());
        assertEquals(List.of(48, 24), NotificationSettingsRepository.parse("48, 24"));
        assertEquals(List.of(), NotificationSettingsRepository.parse(null));
    }
}
