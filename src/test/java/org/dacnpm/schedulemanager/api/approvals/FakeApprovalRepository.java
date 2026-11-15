package org.dacnpm.schedulemanager.api.approvals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bước duyệt trong bộ nhớ, mô phỏng điều kiện của các câu UPDATE trong {@link ApprovalRepository}. */
public class FakeApprovalRepository extends ApprovalRepository {

    public final List<ApprovalFlow> flows = new ArrayList<>();
    public final List<ApprovalStep> steps = new ArrayList<>();
    /** userId → managerId (đang hoạt động). */
    public final Map<String, String> managers = new HashMap<>();
    private int seq;

    public FakeApprovalRepository() {
        super(null);
    }

    public void mode(String subject, String leaveType, String mode) {
        flows.removeIf(f -> f.subjectType().equals(subject) && Objects.equals(f.leaveTypeId(), leaveType));
        flows.add(new ApprovalFlow("flow" + (++seq), subject, leaveType, null, mode, null, null, 0));
    }

    @Override public List<ApprovalFlow> findFlows() { return flows; }

    @Override public Optional<ApprovalFlow> findEffectiveFlow(String subject, String leaveType) {
        Optional<ApprovalFlow> specific = flows.stream().filter(f -> f.subjectType().equals(subject)
                && leaveType != null && leaveType.equals(f.leaveTypeId())).findFirst();
        return specific.isPresent() ? specific
                : flows.stream().filter(f -> f.subjectType().equals(subject) && f.leaveTypeId() == null).findFirst();
    }

    @Override public Optional<ApprovalFlow> findFlow(String subject, String leaveType) {
        return flows.stream().filter(f -> f.subjectType().equals(subject) && Objects.equals(f.leaveTypeId(), leaveType))
                .findFirst();
    }

    @Override public void insertFlow(String subject, String leaveType, String mode, String actor) {
        mode(subject, leaveType, mode);
    }

    @Override public int updateFlow(String id, int expectedVersion, String mode, String actor) {
        for (int i = 0; i < flows.size(); i++) {
            ApprovalFlow f = flows.get(i);
            if (f.id().equals(id) && f.version() == expectedVersion) {
                flows.set(i, new ApprovalFlow(f.id(), f.subjectType(), f.leaveTypeId(), null, mode, actor, null,
                        f.version() + 1));
                return 1;
            }
        }
        return 0;
    }

    @Override public Optional<String> activeManagerOf(String userId) {
        return Optional.ofNullable(managers.get(userId));
    }

    @Override public boolean isManagerOf(String managerId, String userId) {
        return managerId.equals(managers.get(userId));
    }

    @Override public boolean hasReports(String managerId) {
        return managers.containsValue(managerId);
    }

    @Override public void insertStep(String subject, String subjectId, int stepNo, String kind, String approverId,
                                     String status, String note) {
        if (steps.stream().anyMatch(s -> s.subjectId().equals(subjectId) && s.stepNo() == stepNo)) {
            throw new org.springframework.dao.DuplicateKeyException("uq_approval_steps_order");
        }
        steps.add(new ApprovalStep("st" + (++seq), subject, subjectId, stepNo, kind, approverId, approverId, status,
                null, null, null, note, 0));
    }

    @Override public List<ApprovalStep> findSteps(String subject, String subjectId) {
        return steps.stream().filter(s -> s.subjectType().equals(subject) && s.subjectId().equals(subjectId))
                .sorted(java.util.Comparator.comparingInt(ApprovalStep::stepNo)).toList();
    }

    private int replace(String id, java.util.function.Predicate<ApprovalStep> cond,
                        java.util.function.UnaryOperator<ApprovalStep> change) {
        for (int i = 0; i < steps.size(); i++) {
            ApprovalStep s = steps.get(i);
            if (s.id().equals(id) && cond.test(s)) {
                steps.set(i, change.apply(s));
                return 1;
            }
        }
        return 0;
    }

    private static ApprovalStep with(ApprovalStep s, String status, String decider, String note) {
        return new ApprovalStep(s.id(), s.subjectType(), s.subjectId(), s.stepNo(), s.approverKind(), s.approverId(),
                s.approverName(), status, decider, decider, null, note, s.version() + 1);
    }

    @Override public int decideStep(String id, int expectedVersion, String from, String to, String decider, String note) {
        return replace(id, s -> s.version() == expectedVersion && s.status().equals(from), s -> with(s, to, decider, note));
    }

    @Override public int activateStep(String id) {
        return replace(id, s -> s.status().equals("waiting"), s -> with(s, "pending", null, null));
    }

    @Override public int skipOpenSteps(String subject, String subjectId, String decider, String note) {
        int n = 0;
        for (ApprovalStep s : List.copyOf(steps)) {
            if (s.subjectId().equals(subjectId) && s.open()) n += replace(s.id(), x -> true, x -> with(x, "skipped", decider, note));
        }
        return n;
    }

    @Override public List<String> pendingSubjectsFor(String approverId, String subject) {
        return steps.stream().filter(s -> approverId.equals(s.approverId()) && s.subjectType().equals(subject) && s.pending())
                .map(ApprovalStep::subjectId).toList();
    }
}
