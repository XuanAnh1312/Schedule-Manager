package org.dacnpm.schedulemanager.api.rules;

import org.dacnpm.schedulemanager.api.rules.RuleModels.EffectiveRules;
import org.dacnpm.schedulemanager.api.rules.RuleModels.LevelSubstitutionsInfo;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ScopeContext;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Setting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quy tắc có hiệu lực (TU-06), giới hạn fixed/percent tự tính (AC-01, AC-14), checklist thiếu (AC-13). */
class EffectiveRuleResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    private static final ScopeContext NV1 = new ScopeContext("nv001", null, null, null, null);
    private static final LevelSubstitutionsInfo NO_LEVELS = new LevelSubstitutionsInfo(List.of(), false, null);

    @Test
    void percentMode_60hTarget_125percent_givesMax75() {
        EffectiveRules r = resolve(complete("percent", "125", null));
        assertEquals(0, new BigDecimal("75").compareTo(r.derived().maxHours()));
        assertEquals("60 × 125% = 75", r.derived().maxHoursFormula());
        assertTrue(r.readyForScheduling());
        assertTrue(r.missing().isEmpty());
    }

    @Test
    void fixedMode_usesFixedHours() {
        EffectiveRules r = resolve(complete("fixed", null, "70"));
        assertEquals(0, new BigDecimal("70").compareTo(r.derived().maxHours()));
        assertEquals("fixed", r.derived().maxHoursBasis());
    }

    @Test
    void nothingConfigured_listsEveryRequiredParameter_andNeverInventsValues() {
        EffectiveRules r = resolve(List.of());
        assertFalse(r.readyForScheduling());
        assertNull(r.derived().maxHours());
        List<String> keys = r.missing().stream().map(RuleModels.Missing::key).toList();
        assertEquals(List.of("target_hours", "max_hours_mode", "max_hours_per_day", "max_hours_per_week",
                "min_rest_hours", "max_consecutive_days"), keys);
    }

    @Test
    void percentModeWithoutPercent_isMissing() {
        List<Setting> s = complete("percent", null, null);
        EffectiveRules r = resolve(s);
        assertTrue(r.missing().stream().anyMatch(m -> m.key().equals("max_hours_percent")));
        assertFalse(r.readyForScheduling());
    }

    @Test
    void userScope_overridesCompany_andReportsSource() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(setting("target_hours", "user", "nv001", "40", TODAY));
        EffectiveRules r = resolve(s);
        assertEquals(0, new BigDecimal("50").compareTo(r.derived().maxHours()), "40 × 125% = 50");
        var target = r.values().stream().filter(v -> v.key().equals("target_hours")).findFirst().orElseThrow();
        assertEquals("user", target.source().scopeType());
        assertEquals("nv001", target.source().scopeId());
    }

    @Test
    void otherUsersOverride_doesNotApply() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(setting("target_hours", "user", "nv999", "40", TODAY));
        assertEquals(0, new BigDecimal("75").compareTo(resolve(s).derived().maxHours()));
    }

    @Test
    void futureValue_isNotEffectiveYet_latestPastValueWins() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(setting("max_hours_percent", "company", null, "110", TODAY.minusDays(10)));
        s.add(setting("max_hours_percent", "company", null, "150", TODAY.plusDays(5)));
        assertEquals(0, new BigDecimal("75").compareTo(resolve(s).derived().maxHours()), "125 (today) beats 110 (older)");
        EffectiveRules later = EffectiveRuleResolver.resolve(s, NV1, TODAY.plusDays(5), NO_LEVELS);
        assertEquals(0, new BigDecimal("90").compareTo(later.derived().maxHours()), "60 × 150% from its effective date");
    }

    @Test
    void maxBelowTarget_isReportedAndBlocksScheduling() {
        EffectiveRules r = resolve(complete("fixed", null, "50"));
        assertTrue(r.issues().stream().anyMatch(i -> i.startsWith("MAX_BELOW_TARGET")));
        assertFalse(r.readyForScheduling());
    }

    @Test
    void warningHours_isDerivedFromThreshold() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(setting("warning_threshold_percent", "company", null, "90", TODAY));
        assertEquals(0, new BigDecimal("67.5").compareTo(resolve(s).derived().warningHours()));
    }

    @Test
    void inactiveSettings_areIgnored() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(new Setting("x", "target_hours", "user", "nv001", "10", TODAY, "superseded", "c", null, null));
        assertEquals(0, new BigDecimal("75").compareTo(resolve(s).derived().maxHours()));
    }

    // ── TU-R1: scope thực tế, thứ tự ưu tiên và xung đột cùng mức ──────────────

    @Test
    void sameDay_employeesOfDifferentBranches_getTheirBranchLimit() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(setting("max_hours_per_week", "branch", "b1", "40", TODAY));
        s.add(setting("max_hours_per_week", "branch", "b2", "44", TODAY));

        EffectiveRules first = EffectiveRuleResolver.resolve(s, new ScopeContext("nv001", "b1", null, null, null), TODAY, NO_LEVELS);
        EffectiveRules second = EffectiveRuleResolver.resolve(s, new ScopeContext("nv002", "b2", null, null, null), TODAY, NO_LEVELS);
        EffectiveRules noBranch = resolve(s);

        assertEquals("40", value(first, "max_hours_per_week"));
        assertEquals("branch", source(first, "max_hours_per_week").scopeType());
        assertEquals("44", value(second, "max_hours_per_week"));
        assertEquals("48", value(noBranch, "max_hours_per_week"));
    }

    @Test
    void precedence_userOverGroupOverEmployeeTypeOverBranchOverCompany() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(setting("max_hours_per_week", "branch", "b1", "40", TODAY));
        s.add(setting("max_hours_per_week", "employee_type", "pt", "30", TODAY));
        s.add(setting("max_hours_per_week", "group", "g1", "25", TODAY));
        ScopeContext all = new ScopeContext("nv001", "b1", "pt", "g1", null);

        assertEquals("25", value(EffectiveRuleResolver.resolve(s, all, TODAY, NO_LEVELS), "max_hours_per_week"));
        s.add(setting("max_hours_per_week", "user", "nv001", "20", TODAY));
        EffectiveRules withUser = EffectiveRuleResolver.resolve(s, all, TODAY, NO_LEVELS);
        assertEquals("20", value(withUser, "max_hours_per_week"));
        assertEquals("chg", source(withUser, "max_hours_per_week").changeId());
    }

    @Test
    void twoValuesAtTheSameScopeAndDate_areAConfigurationConflict_notARandomPick() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(new Setting("dup-a", "max_hours_per_week", "group", "g1", "25", TODAY, "active", "c1", null, null));
        s.add(new Setting("dup-b", "max_hours_per_week", "group", "g1", "30", TODAY, "active", "c2", null, null));

        EffectiveRules r = EffectiveRuleResolver.resolve(s, new ScopeContext("nv001", null, null, "g1", null), TODAY, NO_LEVELS);

        assertFalse(r.readyForScheduling());
        assertTrue(r.issues().stream().anyMatch(i -> i.startsWith("CONFLICT: max_hours_per_week")));
        assertNull(value(r, "max_hours_per_week"));
        assertTrue(r.missing().stream().noneMatch(m -> m.key().equals("max_hours_per_week")));
    }

    @Test
    void newerValueAtTheSameScope_isNotAConflict() {
        List<Setting> s = new ArrayList<>(complete("percent", "125", null));
        s.add(setting("max_hours_per_week", "group", "g1", "25", TODAY.minusDays(10)));
        s.add(setting("max_hours_per_week", "group", "g1", "30", TODAY));

        EffectiveRules r = EffectiveRuleResolver.resolve(s, new ScopeContext("nv001", null, null, "g1", null), TODAY, NO_LEVELS);

        assertTrue(r.readyForScheduling());
        assertEquals("30", value(r, "max_hours_per_week"));
    }

    private static String value(EffectiveRules r, String key) {
        return r.values().stream().filter(v -> v.key().equals(key)).map(RuleModels.EffectiveValue::value).findFirst().orElse(null);
    }

    private static RuleModels.Source source(EffectiveRules r, String key) {
        return r.values().stream().filter(v -> v.key().equals(key)).map(RuleModels.EffectiveValue::source).findFirst().orElseThrow();
    }

    private static EffectiveRules resolve(List<Setting> settings) {
        return EffectiveRuleResolver.resolve(settings, NV1, TODAY, NO_LEVELS);
    }

    private static List<Setting> complete(String mode, String percent, String fixed) {
        List<Setting> s = new ArrayList<>();
        s.add(setting("target_hours", "company", null, "60", TODAY));
        s.add(setting("max_hours_mode", "company", null, mode, TODAY));
        if (percent != null) s.add(setting("max_hours_percent", "company", null, percent, TODAY));
        if (fixed != null) s.add(setting("max_hours_fixed", "company", null, fixed, TODAY));
        s.add(setting("max_hours_per_day", "company", null, "12", TODAY));
        s.add(setting("max_hours_per_week", "company", null, "48", TODAY));
        s.add(setting("min_rest_hours", "company", null, "8", TODAY));
        s.add(setting("max_consecutive_days", "company", null, "6", TODAY));
        return s;
    }

    private static Setting setting(String key, String scope, String scopeId, String value, LocalDate from) {
        return new Setting(key + "-" + scope + "-" + from, key, scope, scopeId, value, from, "active", "chg", null, null);
    }
}
