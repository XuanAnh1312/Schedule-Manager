package org.dacnpm.schedulemanager.api.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dacnpm.schedulemanager.api.periods.PeriodRepository;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ApplyRequest;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeItem;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeRequest;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Preview;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Cài đặt quy tắc: xem trước không ghi, áp dụng cần xác nhận, chống ghi đè, không đụng lịch (AC-13). */
class RuleServiceTest {

    private static final CurrentUser ADMIN = new CurrentUser("usr_admin_001", true);
    private static final CurrentUser EMPLOYEE = new CurrentUser("nv001", false);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    private RuleRepository repository;
    private PeriodRepository periods;
    private AuditTrail audit;
    private ObjectMapper json;
    private RuleService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuleRepository.class);
        periods = mock(PeriodRepository.class);
        audit = mock(AuditTrail.class);
        json = new ObjectMapper().findAndRegisterModules();
        service = new RuleService(repository, periods, audit, json,
                MutableClock.atVn(LocalDateTime.of(2026, 10, 29, 10, 0)));
        when(repository.findActiveSettings()).thenReturn(List.of());
        when(repository.findActiveEmployees()).thenReturn(List.of(
                new RuleRepository.EmployeeRow("nv001", "NV001", "An", "user", "active")));
        when(repository.findUser("nv001")).thenReturn(Optional.of(
                new RuleRepository.EmployeeRow("nv001", "NV001", "An", "user", "active")));
        when(repository.findLevelIds()).thenReturn(java.util.Set.of("lvl1", "lvl2", "lvl3", "lvl4"));
    }

    @Test
    void employee_cannotPreviewOrApply() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.preview(EMPLOYEE, request("target_hours", "60"))).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.apply(EMPLOYEE, "c1", new ApplyRequest(true))).getStatus());
    }

    @Test
    void preview_computesImpact_butWritesNoSettings() {
        Preview preview = service.preview(ADMIN, new ChangeRequest(List.of(
                item("target_hours", "60"), item("max_hours_mode", "percent"), item("max_hours_percent", "125")),
                "Cấu hình AC-01"));

        assertEquals(1, preview.affectedUsers());
        assertTrue(preview.userChanges().get(0).changes().stream()
                .anyMatch(c -> c.key().equals("derived.max_hours") && "75".equals(c.after())));
        verify(repository).insertChange(eq(preview.changeId()), eq(0), eq("Cấu hình AC-01"), anyString(), anyString(),
                eq(ADMIN.id()));
        verify(repository, never()).insertSetting(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).bumpRevision(anyInt());
    }

    @Test
    void preview_rejectsInvalidValues_scopesAndPastDates() {
        ChangeRequest bad = new ChangeRequest(List.of(
                item("max_hours_percent", "abc"),
                new ChangeItem("set", "target_hours", "branch", "b1", "10", null),
                new ChangeItem("set", "weight_cost", "user", "nv001", "5", null),
                new ChangeItem("set", "min_rest_hours", "company", null, "8", TODAY.minusDays(1)),
                item("unknown_key", "1"),
                item("max_consecutive_days", "2.5"),
                item("max_hours_mode", "sometimes")), "x");
        ApiException ex = assertThrows(ApiException.class, () -> service.preview(ADMIN, bad));
        assertEquals(7, ex.getDetails().size(), ex.getDetails().toString());
        verify(repository, never()).insertChange(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void preview_requiresReason() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.preview(ADMIN, new ChangeRequest(List.of(item("target_hours", "60")), " ")));
        assertTrue(ex.getDetails().get(0).startsWith("reason"));
    }

    @Test
    void apply_requiresExplicitConfirmation() {
        assertEquals("CONFIRM_REQUIRED", assertThrows(ApiException.class,
                () -> service.apply(ADMIN, "c1", new ApplyRequest(false))).getCode());
        verify(repository, never()).findChangeForUpdate(any());
    }

    @Test
    void apply_staleRevision_isRejected_andWritesNothing() throws Exception {
        when(repository.findChangeForUpdate("c1")).thenReturn(Optional.of(change("c1", 3)));
        when(repository.bumpRevision(3)).thenReturn(false);
        assertEquals("STALE_PREVIEW", assertThrows(ApiException.class,
                () -> service.apply(ADMIN, "c1", new ApplyRequest(true))).getCode());
        verify(repository, never()).insertSetting(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void apply_writesOnlyRuleTables_keepsHistory_andNeverTouchesPeriodsOrSchedules() throws Exception {
        when(repository.findChangeForUpdate("c1")).thenReturn(Optional.of(change("c1", 0)));
        when(repository.bumpRevision(0)).thenReturn(true);
        when(repository.findChange("c1")).thenReturn(Optional.of(change("c1", 0)));

        service.apply(ADMIN, "c1", new ApplyRequest(true));

        verify(repository).supersede("target_hours", "company", null, TODAY, "c1");
        verify(repository).insertSetting(anyString(), eq("target_hours"), eq("company"), eq(null), eq("60"), eq(TODAY),
                eq("c1"), eq(ADMIN.id()));
        verify(repository).markApplied("c1", ADMIN.id());
        verify(audit).record(eq("rules.applied"), any(), any(), eq(ADMIN.id()), any(), any(), eq("Cấu hình AC-01"));
        verifyNoInteractions(periods);
    }

    @Test
    void apply_alreadyApplied_isConflict() throws Exception {
        RuleRepository.ChangeRow applied = new RuleRepository.ChangeRow("c1", "applied", 0, "r",
                json.writeValueAsString(List.of(item("target_hours", "60"))), null, ADMIN.id(), null, ADMIN.id(), null);
        when(repository.findChangeForUpdate("c1")).thenReturn(Optional.of(applied));
        assertEquals("CHANGE_NOT_PENDING", assertThrows(ApiException.class,
                () -> service.apply(ADMIN, "c1", new ApplyRequest(true))).getCode());
    }

    @Test
    void employee_seesOnlyOwnEffectiveRules() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.effective(EMPLOYEE, "nv002", null)).getStatus());
    }

    @Test
    void scheduling_usesTheEmployeeScopeContextFromTheDatabase() {
        when(repository.scopeContext("nv001")).thenReturn(Optional.of(
                new RuleModels.ScopeContext("nv001", "b1", "pt", "g1", null)));

        RuleModels.EffectiveRules rules = service.effectiveForScheduling("nv001", TODAY);

        assertEquals("b1", rules.scopeContext().branchId());
        assertEquals("g1", rules.scopeContext().groupId());
    }

    @Test
    void scheduling_withoutAnEmployeeRecord_isNotReady_insteadOfFallingBackToCompanyRules() {
        when(repository.scopeContext("ghost")).thenReturn(Optional.empty());

        RuleModels.EffectiveRules rules = service.effectiveForScheduling("ghost", TODAY);

        assertTrue(!rules.readyForScheduling());
        assertTrue(rules.issues().stream().anyMatch(i -> i.startsWith("SCOPE_CONTEXT_MISSING")));
    }

    @Test
    void templates_areOnlySuggestions_andNeverIncludeConsecutiveDays() {
        when(repository.findTemplate()).thenReturn(Optional.of(new RuleRepository.TemplateRow(
                new java.math.BigDecimal("80"), new java.math.BigDecimal("12"), new java.math.BigDecimal("48"),
                new java.math.BigDecimal("80"), new java.math.BigDecimal("8"))));
        var template = service.templates(ADMIN);
        assertTrue(template.note().startsWith("ĐỀ XUẤT"));
        assertTrue(template.items().stream().noneMatch(i -> i.paramKey().equals("max_consecutive_days")));
        verify(repository, never()).insertSetting(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private RuleRepository.ChangeRow change(String id, int baseRevision) throws Exception {
        ChangeItem normalized = new ChangeItem("set", "target_hours", "company", null, "60", TODAY);
        return new RuleRepository.ChangeRow(id, "previewed", baseRevision, "Cấu hình AC-01",
                json.writeValueAsString(List.of(normalized)), null, ADMIN.id(), null, null, null);
    }

    private static ChangeRequest request(String key, String value) {
        return new ChangeRequest(List.of(item(key, value)), "lý do");
    }

    private static ChangeItem item(String key, String value) {
        return new ChangeItem(null, key, null, null, value, null);
    }
}
