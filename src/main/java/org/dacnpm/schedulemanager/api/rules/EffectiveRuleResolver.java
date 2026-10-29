package org.dacnpm.schedulemanager.api.rules;

import org.dacnpm.schedulemanager.api.rules.RuleModels.Derived;
import org.dacnpm.schedulemanager.api.rules.RuleModels.EffectiveRules;
import org.dacnpm.schedulemanager.api.rules.RuleModels.EffectiveValue;
import org.dacnpm.schedulemanager.api.rules.RuleModels.LevelSubstitutionsInfo;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Missing;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ScopeContext;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Setting;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tính quy tắc có hiệu lực cho một nhân viên tại một ngày (TU-06). Logic thuần, không truy cập DB.
 *
 * <ol>
 *   <li>Với mỗi tham số, chỉ xét giá trị active có {@code effectiveFrom <= date} thuộc phạm vi khớp
 *       với nhân viên.</li>
 *   <li>Phạm vi cụ thể hơn thắng ({@link RuleScopeType#precedence()}); cùng phạm vi thì ngày hiệu lực
 *       mới nhất thắng.</li>
 *   <li>Giờ tối đa được suy ra: fixed lấy trực tiếp, percent = mục tiêu × % / 100 (AC-01, AC-14).</li>
 *   <li>Thiếu tham số bắt buộc → checklist {@code missing}; không tự điền giá trị nghiệp vụ.</li>
 * </ol>
 */
public final class EffectiveRuleResolver {

    private static final int HOURS_SCALE = 2;

    private EffectiveRuleResolver() {}

    public static EffectiveRules resolve(List<Setting> activeSettings, ScopeContext context, LocalDate date,
                                         LevelSubstitutionsInfo levelSubstitutions) {
        Map<RuleParameter, EffectiveValue> chosen = new EnumMap<>(RuleParameter.class);
        for (RuleParameter parameter : RuleParameter.values()) {
            if (parameter == RuleParameter.LEVEL_SUBSTITUTIONS) {
                continue; // nguồn runtime là bảng level_substitutions, trả riêng
            }
            pick(activeSettings, parameter, context, date).ifPresent(s -> chosen.put(parameter, toValue(parameter, s)));
        }

        List<Missing> missing = new ArrayList<>();
        for (RuleParameter parameter : RuleParameter.values()) {
            if (parameter.requiredForScheduling() && !chosen.containsKey(parameter)) {
                missing.add(new Missing(parameter.key(), parameter.label(), "Chưa cấu hình."));
            }
        }
        String mode = value(chosen, RuleParameter.MAX_HOURS_MODE);
        if ("fixed".equals(mode) && !chosen.containsKey(RuleParameter.MAX_HOURS_FIXED)) {
            missing.add(new Missing(RuleParameter.MAX_HOURS_FIXED.key(), RuleParameter.MAX_HOURS_FIXED.label(),
                    "Cách đặt giờ tối đa là fixed nhưng chưa có số giờ tối đa."));
        }
        if ("percent".equals(mode) && !chosen.containsKey(RuleParameter.MAX_HOURS_PERCENT)) {
            missing.add(new Missing(RuleParameter.MAX_HOURS_PERCENT.key(), RuleParameter.MAX_HOURS_PERCENT.label(),
                    "Cách đặt giờ tối đa là percent nhưng chưa có tỷ lệ %."));
        }

        Derived derived = derive(chosen, mode);
        List<String> issues = new ArrayList<>();
        if (derived.maxHours() != null && derived.targetHours() != null
                && derived.maxHours().compareTo(derived.targetHours()) < 0) {
            issues.add("MAX_BELOW_TARGET: giờ tối đa " + plain(derived.maxHours())
                    + " nhỏ hơn giờ mục tiêu " + plain(derived.targetHours()) + ".");
        }
        BigDecimal perDay = decimal(chosen, RuleParameter.MAX_HOURS_PER_DAY);
        BigDecimal perWeek = decimal(chosen, RuleParameter.MAX_HOURS_PER_WEEK);
        if (perDay != null && perWeek != null && perDay.compareTo(perWeek) > 0) {
            issues.add("DAY_ABOVE_WEEK: giờ tối đa mỗi ngày lớn hơn giờ tối đa mỗi tuần.");
        }

        List<EffectiveValue> values = chosen.values().stream()
                .sorted(Comparator.comparingInt((EffectiveValue v) ->
                        RuleParameter.byKey(v.key()).orElseThrow().category().priority())
                        .thenComparing(v -> RuleParameter.byKey(v.key()).orElseThrow().ordinal()))
                .toList();
        return new EffectiveRules(context.userId(), date, context, values, derived, List.copyOf(missing),
                List.copyOf(issues), missing.isEmpty() && issues.isEmpty(), levelSubstitutions);
    }

    /** Giá trị active thắng cho một tham số, hoặc rỗng. */
    static Optional<Setting> pick(List<Setting> settings, RuleParameter parameter, ScopeContext context, LocalDate date) {
        return settings.stream()
                .filter(s -> parameter.key().equals(s.paramKey()))
                .filter(s -> "active".equals(s.status()))
                .filter(s -> !s.effectiveFrom().isAfter(date))
                .filter(s -> matches(s, context))
                .max(Comparator.comparingInt((Setting s) -> scopeOf(s).precedence())
                        .thenComparing(Setting::effectiveFrom));
    }

    private static boolean matches(Setting s, ScopeContext c) {
        RuleScopeType scope = scopeOf(s);
        return switch (scope) {
            case COMPANY -> true;
            case BRANCH -> Objects.equals(s.scopeId(), c.branchId());
            case EMPLOYEE_TYPE -> Objects.equals(s.scopeId(), c.employeeTypeId());
            case GROUP -> Objects.equals(s.scopeId(), c.groupId());
            case USER -> Objects.equals(s.scopeId(), c.userId());
        };
    }

    private static RuleScopeType scopeOf(Setting s) {
        return RuleScopeType.fromDb(s.scopeType())
                .orElseThrow(() -> new IllegalStateException("Unknown rule scope: " + s.scopeType()));
    }

    private static Derived derive(Map<RuleParameter, EffectiveValue> chosen, String mode) {
        BigDecimal target = decimal(chosen, RuleParameter.TARGET_HOURS);
        BigDecimal max = null;
        String formula = null;
        if ("fixed".equals(mode)) {
            max = decimal(chosen, RuleParameter.MAX_HOURS_FIXED);
            formula = max == null ? null : "cố định " + plain(max) + " giờ";
        } else if ("percent".equals(mode)) {
            BigDecimal percent = decimal(chosen, RuleParameter.MAX_HOURS_PERCENT);
            if (target != null && percent != null) {
                max = maxHoursFromPercent(target, percent);
                formula = plain(target) + " × " + plain(percent) + "% = " + plain(max);
            }
        }
        BigDecimal threshold = decimal(chosen, RuleParameter.WARNING_THRESHOLD_PERCENT);
        BigDecimal warning = max != null && threshold != null
                ? max.multiply(threshold).divide(BigDecimal.valueOf(100), HOURS_SCALE, RoundingMode.HALF_UP)
                : null;
        return new Derived(target, mode, max, formula, warning);
    }

    /** giờ tối đa = giờ mục tiêu × tỷ lệ / 100, làm tròn 2 chữ số. */
    public static BigDecimal maxHoursFromPercent(BigDecimal target, BigDecimal percent) {
        return target.multiply(percent).divide(BigDecimal.valueOf(100), HOURS_SCALE, RoundingMode.HALF_UP);
    }

    private static EffectiveValue toValue(RuleParameter p, Setting s) {
        return new EffectiveValue(p.key(), p.label(), p.unit(), p.kind().name().toLowerCase(Locale.ROOT),
                p.category().name().toLowerCase(Locale.ROOT), s.value(),
                new Source(s.scopeType(), s.scopeId(), s.effectiveFrom(), s.id(), s.changeId()));
    }

    private static String value(Map<RuleParameter, EffectiveValue> chosen, RuleParameter p) {
        EffectiveValue v = chosen.get(p);
        return v == null ? null : v.value();
    }

    private static BigDecimal decimal(Map<RuleParameter, EffectiveValue> chosen, RuleParameter p) {
        String v = value(chosen, p);
        return v == null ? null : new BigDecimal(v);
    }

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
