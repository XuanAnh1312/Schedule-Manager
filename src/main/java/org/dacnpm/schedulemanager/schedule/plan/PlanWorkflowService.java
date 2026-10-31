package org.dacnpm.schedulemanager.schedule.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.dacnpm.schedulemanager.schedule.events.ScheduleEvent;
import org.dacnpm.schedulemanager.schedule.events.ScheduleEventSink;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionRepository;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleRepository;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleRepository.OfficialAssignment;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.Workflow;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.RosterChecker.PairCheck;
import org.dacnpm.schedulemanager.schedule.plan.RosterChecker.RosterCheck;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.validation.Violation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Luồng phương án (TW-05): draft → pending_approval → approved → published; trả về nháp khi từ chối; bản đã công bố
 * trước đó của kỳ chuyển sang superseded. Mọi chuyển trạng thái có điều kiện version (khóa lạc quan) và ghi lịch sử.
 *
 * <p>Công bố (TW-06) trong một giao dịch: khóa dòng kỳ, kiểm tra lại toàn bộ roster bằng cùng bộ kiểm tra, đặt lịch
 * chính thức của các ca trong phương án đúng bằng roster, đánh dấu ca đã công bố, phát sự kiện. Lỗi ở bất kỳ bước nào
 * làm rollback toàn bộ, kể cả sự kiện.
 */
@Profile("sqlserver")
@Service
public class PlanWorkflowService {

    public static final String UNDERSTAFFED_BLOCK = "block";

    /**
     * Mức sẵn sàng của phương án.
     *
     * @param missingExceptions cặp có vi phạm thuộc nhóm ngoại lệ nhưng chưa có ngoại lệ được duyệt
     */
    public record Readiness(String planId, String status, int version, RosterCheck check,
                            List<PairCheck> missingExceptions, List<RosterChecker.ShiftCoverage> understaffed,
                            String understaffedPolicy, boolean canSubmit, boolean canPublish) {
    }

    public record WorkflowView(PlanView plan, Workflow workflow) {
    }

    public record PublishReport(String planId, int version, List<String> publishedShiftIds, int added, int removed,
                                int kept, List<String> supersededPlans, List<String> notifiedUsers, WorkflowView plan) {
    }

    private final PlanRepository plans;
    private final PlanService planService;
    private final ScheduleDataRepository data;
    private final RosterChecker checker;
    private final OfficialScheduleRepository official;
    private final ExceptionRepository exceptions;
    private final ShiftRepository history;
    private final ScheduleEventSink events;

    public PlanWorkflowService(PlanRepository plans, PlanService planService, ScheduleDataRepository data,
                               RosterChecker checker, OfficialScheduleRepository official, ExceptionRepository exceptions,
                               ShiftRepository history, ScheduleEventSink events) {
        this.plans = plans;
        this.planService = planService;
        this.data = data;
        this.checker = checker;
        this.official = official;
        this.exceptions = exceptions;
        this.history = history;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public WorkflowView view(String planId) {
        return new WorkflowView(planService.get(planId), plans.workflow(planId).orElse(null));
    }

    /** Kiểm tra toàn bộ roster: vi phạm bắt buộc, ngoại lệ còn thiếu, ca thiếu người. Không ghi gì. */
    @Transactional(readOnly = true)
    public Readiness readiness(String planId) {
        return readiness(planService.get(planId));
    }

    @Transactional
    public WorkflowView submit(String planId, int expectedVersion, String actorId) {
        PlanView plan = planService.get(planId);
        requireStatus(plan, PlanRepository.DRAFT, "gửi duyệt");
        requireVersion(plan, expectedVersion);
        Readiness r = readiness(plan);
        rejectHardViolations(r);
        if (!plans.submit(planId, expectedVersion, actorId)) {
            throw conflict();
        }
        history.writeHistory("plan_submit", "Gửi duyệt phương án " + plan.name(), summary(planId, r), actorId,
                ShiftRepository.auditJson(java.util.Map.of("planId",planId,"status",plan.status(),"version",expectedVersion)),
                ShiftRepository.auditJson(java.util.Map.of("planId",planId,"status","pending_approval","version",expectedVersion+1)), null);
        return view(planId);
    }

    @Transactional
    public WorkflowView approve(String planId, int expectedVersion, String note, String actorId) {
        PlanView plan = planService.get(planId);
        requireStatus(plan, PlanRepository.PENDING_APPROVAL, "duyệt");
        requireVersion(plan, expectedVersion);
        Readiness r = readiness(plan);
        rejectHardViolations(r);
        if (!plans.review(planId, expectedVersion, PlanRepository.APPROVED, actorId, trimOrNull(note))) {
            throw conflict();
        }
        history.writeHistory("plan_approve", "Duyệt phương án " + plan.name(), summary(planId, r), actorId,
                ShiftRepository.auditJson(java.util.Map.of("planId",planId,"status",plan.status(),"version",expectedVersion)),
                ShiftRepository.auditJson(java.util.Map.of("planId",planId,"status","approved","version",expectedVersion+1)), trimOrNull(note));
        return view(planId);
    }

    /** Từ chối: trả phương án về nháp, bắt buộc có lý do. */
    @Transactional
    public WorkflowView reject(String planId, int expectedVersion, String reason, String actorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Lý do trả về không được để trống.");
        }
        PlanView plan = planService.get(planId);
        requireStatus(plan, PlanRepository.PENDING_APPROVAL, "trả về");
        requireVersion(plan, expectedVersion);
        if (!plans.review(planId, expectedVersion, PlanRepository.DRAFT, actorId, reason.trim())) {
            throw conflict();
        }
        history.writeHistory("plan_reject", "Trả phương án về nháp " + plan.name(), planId, actorId,
                ShiftRepository.auditJson(java.util.Map.of("planId",planId,"status",plan.status(),"version",expectedVersion)),
                ShiftRepository.auditJson(java.util.Map.of("planId",planId,"status","draft","version",expectedVersion+1)),
                reason.trim());
        return view(planId);
    }

    /**
     * Công bố phương án đã duyệt vào lịch chính thức.
     *
     * @param confirmUnderstaffed xác nhận công bố khi còn ca thiếu người (chỉ khi chính sách là warn)
     */
    @Transactional
    public PublishReport publish(String planId, int expectedVersion, boolean confirmUnderstaffed, String actorId) {
        PlanView plan = planService.get(planId);
        requireStatus(plan, PlanRepository.APPROVED, "công bố");
        requireVersion(plan, expectedVersion);
        // Khóa trước rồi mới đọc trạng thái kỳ, để kết quả kiểm tra còn đúng tới lúc commit.
        plans.lockPeriod(plan.periodId());
        String periodStatus = plans.periodStatus(plan.periodId()).orElse(null);
        if (!"locked".equals(periodStatus)) {
            throw PlanException.periodNotReady("Chỉ công bố khi kỳ đã khóa đăng ký (locked); kỳ đang " + periodStatus + ".");
        }

        Readiness r = readiness(plan);
        rejectHardViolations(r);
        if (!r.missingExceptions().isEmpty()) {
            throw PlanException.of(PlanException.EXCEPTION_REQUIRED, 409,
                    "Có phân công cần ngoại lệ đã duyệt trước khi công bố.",
                    r.missingExceptions().stream().map(PlanWorkflowService::describe).toList());
        }
        if (!r.understaffed().isEmpty()) {
            List<String> details = r.understaffed().stream()
                    .map(s -> s.name() + " (" + s.shiftId() + "): " + s.filled() + "/" + s.minimum() + " tối thiểu").toList();
            if (UNDERSTAFFED_BLOCK.equals(r.understaffedPolicy())) {
                throw PlanException.of(PlanException.UNDERSTAFFED_BLOCKED, 409,
                        "Chính sách không cho công bố khi còn ca thiếu người.", details);
            }
            if (!confirmUnderstaffed) {
                throw PlanException.of(PlanException.CONFIRM_UNDERSTAFFED, 409,
                        "Còn ca thiếu người; gửi lại với confirmUnderstaffed = true để công bố.", details);
            }
        }

        // Đặt lịch chính thức của các ca trong phương án đúng bằng roster.
        Set<PlanPair> roster = new LinkedHashSet<>(plan.roster());
        List<OfficialAssignment> current = official.confirmedByShifts(plan.shiftIds());
        Map<PlanPair, OfficialAssignment> currentByPair = current.stream()
                .collect(Collectors.toMap(a -> new PlanPair(a.shiftId(), a.userId()), Function.identity(), (a, b) -> a));
        List<String> removedUsers = new ArrayList<>();
        int removed = 0;
        for (Map.Entry<PlanPair, OfficialAssignment> entry : currentByPair.entrySet()) {
            if (!roster.contains(entry.getKey())) {
                OfficialAssignment a = entry.getValue();
                if (!official.cancel(a.id(), a.userId(), a.version(), "Thay bằng phương án " + planId)) {
                    throw conflict();
                }
                removedUsers.add(a.userId());
                removed++;
            }
        }
        int added = 0;
        for (PlanPair pair : roster) {
            if (!currentByPair.containsKey(pair)) {
                official.addOrReactivate(pair.shiftId(), pair.userId(), actorId, "Công bố phương án " + planId, planId);
                added++;
            }
        }
        official.markShiftsPublished(plan.shiftIds());
        List<String> superseded = plans.supersedePublished(plan.periodId(), planId);
        if (!plans.markPublished(planId, expectedVersion, actorId)) {
            throw conflict();
        }
        int newVersion = expectedVersion + 1;

        Set<String> recipients = new LinkedHashSet<>();
        roster.forEach(p -> recipients.add(p.userId()));
        recipients.addAll(removedUsers);
        events.publish(ScheduleEvent.of(ScheduleEvent.SCHEDULE_PUBLISHED, "schedule_plan", planId, newVersion, actorId,
                List.copyOf(recipients), "Lịch mới đã được công bố",
                "Phương án \"" + plan.name() + "\" đã được công bố. Hãy xem và xác nhận lịch của bạn.", "my-schedule"));
        history.writeHistory("plan_publish", "Công bố phương án " + plan.name(),
                planId + ": " + plan.shiftIds().size() + " ca, thêm " + added + ", bỏ " + removed + ", giữ "
                        + (roster.size() - added) + (superseded.isEmpty() ? "." : ", thay bản " + String.join(", ", superseded) + "."),
                actorId, ShiftRepository.auditJson(java.util.Map.of("status",plan.status(),"version",expectedVersion,"assignments",current)),
                ShiftRepository.auditJson(java.util.Map.of("status","published","version",newVersion,"assignments",roster)), null);
        return new PublishReport(planId, newVersion, plan.shiftIds(), added, removed, roster.size() - added, superseded,
                List.copyOf(recipients), view(planId));
    }

    Readiness readiness(PlanView plan) {
        ScheduleData d = data.load(plan.periodId())
                .orElseThrow(() -> PlanException.notFound("Không tìm thấy kỳ " + plan.periodId() + "."));
        RosterCheck check = checker.check(d, plan.shiftIds(), plan.roster());
        List<PairCheck> missing = new ArrayList<>();
        for (PairCheck pair : check.withExceptionViolations()) {
            Set<String> approved = exceptions.activeCodes(plan.id(), pair.shiftId(), pair.userId());
            if (!approved.containsAll(pair.exceptionCodes())) {
                missing.add(pair);
            }
        }
        String policy = plans.understaffedPolicy();
        boolean hard = !check.withHardViolations().isEmpty();
        boolean blockedByStaffing = UNDERSTAFFED_BLOCK.equals(policy) && !check.understaffed().isEmpty();
        return new Readiness(plan.id(), plan.status(), plan.version(), check, missing, check.understaffed(), policy,
                !hard, !hard && missing.isEmpty() && !blockedByStaffing);
    }

    private static void rejectHardViolations(Readiness r) {
        List<PairCheck> hard = r.check().withHardViolations();
        if (!hard.isEmpty()) {
            throw PlanException.of(PlanException.HARD_VIOLATIONS, 422,
                    "Phương án có phân công vi phạm ràng buộc bắt buộc; hãy sửa trước.",
                    hard.stream().map(PlanWorkflowService::describe).toList());
        }
    }

    private static String describe(PairCheck pair) {
        List<Violation> all = new ArrayList<>(pair.hard());
        all.addAll(pair.exceptionable());
        return pair.userId() + " @ " + pair.shiftId() + ": "
                + all.stream().map(Violation::message).collect(Collectors.joining(" "));
    }

    private static String summary(String planId, Readiness r) {
        return planId + ": " + r.check().pairs().size() + " phân công, " + r.understaffed().size() + " ca thiếu người, "
                + r.missingExceptions().size() + " phân công chờ ngoại lệ.";
    }

    private static void requireStatus(PlanView plan, String status, String action) {
        if (!status.equals(plan.status())) {
            throw PlanException.notEditable("Không thể " + action + " phương án đang ở trạng thái " + plan.status() + ".");
        }
    }

    private static void requireVersion(PlanView plan, int expectedVersion) {
        if (plan.version() != expectedVersion) {
            throw PlanException.conflict("Phương án đã được thay đổi (version " + plan.version() + "). Hãy tải lại.");
        }
    }

    private static PlanException conflict() {
        return PlanException.conflict("Dữ liệu đã được người khác thay đổi trong lúc xử lý. Hãy tải lại rồi thử lại.");
    }

    private static String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
