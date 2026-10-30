package org.dacnpm.schedulemanager.schedule.plan;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bảng phương án trong bộ nhớ, đủ cho luồng duyệt/công bố, cùng điều kiện version/trạng thái như bản SQL. */
public class FakePlanRepository extends PlanServiceTest.InMemoryPlans {

    public String policy = "warn";
    public int periodLocks;
    final Map<String, Workflow> workflows = new HashMap<>();

    private boolean transition(String id, int expectedVersion, String from, String to) {
        PlanRow r = rows.get(id);
        if (r == null || r.version() != expectedVersion || !from.equals(r.status())) {
            return false;
        }
        rows.put(id, new PlanRow(id, r.periodId(), r.name(), to, r.version() + 1, r.runCount(), r.shiftIdsJson(),
                r.resultJson(), r.createdBy(), r.createdAt(), LocalDateTime.now()));
        return true;
    }

    @Override
    public boolean submit(String id, int expectedVersion, String actorId) {
        boolean ok = transition(id, expectedVersion, DRAFT, PENDING_APPROVAL);
        if (ok) {
            workflows.put(id, new Workflow(actorId, LocalDateTime.now(), null, null, null, null, null, null));
        }
        return ok;
    }

    @Override
    public boolean review(String id, int expectedVersion, String toStatus, String actorId, String note) {
        return transition(id, expectedVersion, PENDING_APPROVAL, toStatus);
    }

    @Override
    public boolean markPublished(String id, int expectedVersion, String actorId) {
        return transition(id, expectedVersion, APPROVED, PUBLISHED);
    }

    @Override
    public List<String> supersedePublished(String periodId, String newPlanId) {
        List<String> ids = new ArrayList<>();
        for (PlanRow r : new ArrayList<>(rows.values())) {
            if (r.periodId().equals(periodId) && PUBLISHED.equals(r.status()) && !r.id().equals(newPlanId)) {
                transition(r.id(), r.version(), PUBLISHED, SUPERSEDED);
                ids.add(r.id());
            }
        }
        return ids;
    }

    @Override
    public boolean touchDraft(String id, int expectedVersion) {
        return transition(id, expectedVersion, DRAFT, DRAFT);
    }

    @Override
    public void addPair(String planId, PlanPair pair) {
        List<PlanPair> list = new ArrayList<>(assignments(planId));
        if (!list.contains(pair)) {
            list.add(pair);
        }
        pairs.put(planId, list);
    }

    @Override
    public void removePair(String planId, PlanPair pair) {
        List<PlanPair> list = new ArrayList<>(assignments(planId));
        list.remove(pair);
        pairs.put(planId, list);
    }

    @Override
    public String understaffedPolicy() {
        return policy;
    }

    @Override
    public void lockPeriod(String periodId) {
        periodLocks++;
    }

    @Override
    public Optional<Workflow> workflow(String planId) {
        return Optional.ofNullable(workflows.get(planId));
    }

    public void setPeriodStatus(String periodId, String status) {
        periodStatus.put(periodId, status);
    }

    public String status(String planId) {
        return rows.get(planId).status();
    }
}
