package org.dacnpm.schedulemanager.schedule.rules;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dacnpm.schedulemanager.api.rules.EffectiveRuleResolver;
import org.dacnpm.schedulemanager.api.rules.RuleModels;
import org.dacnpm.schedulemanager.schedule.model.Employee;
import org.junit.jupiter.api.Test;

class EffectiveRulesIntegrationTest {
    private RuleModels.EffectiveRules resolve(String mode,String limit) {
        var settings=new ArrayList<RuleModels.Setting>();
        var values=new java.util.LinkedHashMap<>(Map.of("target_hours","60","max_hours_mode",mode,
            "max_hours_per_day","12","max_hours_per_week","80","min_rest_hours","8","max_consecutive_days","6"));
        values.put("percent".equals(mode)?"max_hours_percent":"max_hours_fixed",limit);
        values.forEach((key,value)->settings.add(new RuleModels.Setting(key,key,"company",null,value,LocalDate.of(2026,1,1),"active","c",null,null)));
        return EffectiveRuleResolver.resolve(settings,new RuleModels.ScopeContext("u",null,null,null,null),LocalDate.of(2026,11,2),new RuleModels.LevelSubstitutionsInfo(List.of(),true,null));
    }
    @Test void percentageCeilingIsForThePeriodAndDoesNotUseEmployeeTargetRate() {
        var employee=new Employee("u","Người thử",true,"l",50.0,40.0,Set.of(),Set.of(),Map.of());
        var effective=TableRulesSource.convert(resolve("percent","125"),employee);
        assertThat(effective.ready()).isTrue();
        assertThat(effective.maxHoursPerPeriod()).isEqualTo(75);
        assertThat(effective.targetHours()).isEqualTo(60);
        assertThat(effective.maxHoursPerWeek()).isEqualTo(40);
    }
    @Test void fixedCeilingIsPassedThroughWithoutApplyingPercentAgain() {
        assertThat(TableRulesSource.convert(resolve("fixed","72"),null).maxHoursPerPeriod()).isEqualTo(72);
    }
    @Test void missingConfigurationIsReturnedInsteadOfInventingDefaults() {
        var empty=EffectiveRuleResolver.resolve(List.of(),new RuleModels.ScopeContext("u",null,null,null,null),LocalDate.now(),new RuleModels.LevelSubstitutionsInfo(List.of(),false,null));
        assertThat(TableRulesSource.convert(empty,null).ready()).isFalse();
        assertThat(TableRulesSource.convert(empty,null).maxHoursPerPeriod()).isNull();
    }
}
