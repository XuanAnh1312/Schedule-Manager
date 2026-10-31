package org.dacnpm.schedulemanager.schedule.plan;

import java.util.List;
import org.dacnpm.schedulemanager.api.periods.SchedulePlanGate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("sqlserver")
public class PeriodPlanGate implements SchedulePlanGate {
    private final PlanRepository plans;
    public PeriodPlanGate(PlanRepository plans) { this.plans = plans; }
    @Override
    public List<String> unfinishedPlans(String periodId) { return plans.unfinishedPlans(periodId); }
}
