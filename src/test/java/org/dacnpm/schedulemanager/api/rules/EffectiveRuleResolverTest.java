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
