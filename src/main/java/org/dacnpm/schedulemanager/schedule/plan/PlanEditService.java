package org.dacnpm.schedulemanager.schedule.plan;

import java.util.List;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.Violation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chỉnh thủ công/kéo thả trên phương án nháp (TW-05) với cùng bộ kiểm tra như phân lịch tự động. Vi phạm bắt buộc thì
 * từ chối; vi phạm thuộc nhóm ngoại lệ thì phải đi qua luồng ngoại lệ (ExceptionService). Mỗi lần sửa tăng version của
 * phương án (khóa lạc quan) và ghi lịch sử.
 */
@Profile("sqlserver")
@Service
public class PlanEditService {

    private final PlanRepository plans;
    private final PlanService planService;
    private final ScheduleDataRepository data;
    private final RosterChecker checker;
    private final ShiftRepository history;

    public PlanEditService(PlanRepository plans, PlanService planService, ScheduleDataRepository data,
                           RosterChecker checker, ShiftRepository history) {
        this.plans = plans;
        this.planService = planService;
        this.data = data;
        this.checker = checker;
        this.history = history;
    }

    /** Thêm nhân viên vào ca của phương án. */
    @Transactional
    public PlanView add(String planId, int expectedVersion, String shiftId, String userId, String requiredLevelId,
                        String actorId) {
        PlanView plan = editable(planId, expectedVersion);
        requireShift(plan, shiftId);
        PlanPair pair = new PlanPair(shiftId, userId);
        if (plan.roster().contains(pair)) {
            throw new IllegalArgumentException("Nhân viên " + userId + " đã có trong ca " + shiftId + " của phương án.");
        }
        requireValid(checker.checkPair(load(plan), plan.shiftIds(), plan.roster(), pair, requiredLevelId));
        touch(planId, expectedVersion);
        plans.addPair(planId, pair);
        history.writeHistory("plan_edit", "Thêm phân công vào phương án", planId + ": thêm " + userId + " vào " + shiftId + ".",
                actorId, ShiftRepository.auditJson(java.util.Map.of("assignment",pair,"present",false,"version",expectedVersion)),
                ShiftRepository.auditJson(java.util.Map.of("assignment",pair,"present",true,"version",expectedVersion+1)), null);
        return planService.get(planId);
    }

    @Transactional
    public PlanView remove(String planId, int expectedVersion, String shiftId, String userId, String actorId) {
        PlanView plan = editable(planId, expectedVersion);
        PlanPair pair = new PlanPair(shiftId, userId);
        if (!plan.roster().contains(pair)) {
            throw new IllegalArgumentException("Nhân viên " + userId + " không có trong ca " + shiftId + " của phương án.");
        }
        touch(planId, expectedVersion);
        plans.removePair(planId, pair);
        history.writeHistory("plan_edit", "Bỏ phân công khỏi phương án", planId + ": bỏ " + userId + " khỏi " + shiftId + ".",
                actorId, ShiftRepository.auditJson(java.util.Map.of("assignment",pair,"present",true,"version",expectedVersion)),
                ShiftRepository.auditJson(java.util.Map.of("assignment",pair,"present",false,"version",expectedVersion+1)), null);
        return planService.get(planId);
    }

    /** Kéo thả: chuyển nhân viên từ ca này sang ca khác; kiểm tra ca đích khi đã bỏ phân công ở ca nguồn. */
    @Transactional
    public PlanView move(String planId, int expectedVersion, String userId, String fromShiftId, String toShiftId,
                         String requiredLevelId, String actorId) {
        if (fromShiftId.equals(toShiftId)) {
            throw new IllegalArgumentException("Ca nguồn và ca đích phải khác nhau.");
        }
        PlanView plan = editable(planId, expectedVersion);
        requireShift(plan, toShiftId);
        PlanPair from = new PlanPair(fromShiftId, userId);
        PlanPair to = new PlanPair(toShiftId, userId);
        if (!plan.roster().contains(from)) {
            throw new IllegalArgumentException("Nhân viên " + userId + " không có trong ca " + fromShiftId + " của phương án.");
        }
        if (plan.roster().contains(to)) {
            throw new IllegalArgumentException("Nhân viên " + userId + " đã có trong ca " + toShiftId + " của phương án.");
        }
        List<PlanPair> without = plan.roster().stream().filter(p -> !p.equals(from)).toList();
        requireValid(checker.checkPair(load(plan), plan.shiftIds(), without, to, requiredLevelId));
        touch(planId, expectedVersion);
        plans.removePair(planId, from);
        plans.addPair(planId, to);
        history.writeHistory("plan_edit", "Chuyển phân công trong phương án",
                planId + ": chuyển " + userId + " từ " + fromShiftId + " sang " + toShiftId + ".", actorId,
                ShiftRepository.auditJson(java.util.Map.of("assignment",from,"version",expectedVersion)),
                ShiftRepository.auditJson(java.util.Map.of("assignment",to,"version",expectedVersion+1)),null);
        return planService.get(planId);
    }

    private PlanView editable(String planId, int expectedVersion) {
        PlanView plan = planService.get(planId);
        if (!PlanRepository.DRAFT.equals(plan.status())) {
            throw PlanException.notEditable("Chỉ sửa được phương án nháp; phương án đang " + plan.status()
                    + ". Muốn sửa thì trả về nháp hoặc tạo phương án mới.");
        }
        if (plan.version() != expectedVersion) {
            throw PlanException.conflict("Phương án đã được thay đổi (version " + plan.version() + "). Hãy tải lại.");
        }
        return plan;
    }

    private void touch(String planId, int expectedVersion) {
        if (!plans.touchDraft(planId, expectedVersion)) {
            throw PlanException.conflict("Phương án đã được thay đổi hoặc không còn là nháp. Hãy tải lại.");
        }
    }

    private ScheduleData load(PlanView plan) {
        return data.load(plan.periodId())
                .orElseThrow(() -> PlanException.notFound("Không tìm thấy kỳ " + plan.periodId() + "."));
    }

    private static void requireShift(PlanView plan, String shiftId) {
        if (!plan.shiftIds().contains(shiftId)) {
            throw new IllegalArgumentException("Ca " + shiftId + " không thuộc phương án " + plan.id() + ".");
        }
    }

    private static void requireValid(ValidationResult result) {
        if (result.valid()) {
            return;
        }
        List<String> details = result.violations().stream().map(Violation::message).toList();
        if (result.overridable()) {
            throw PlanException.of(PlanException.EXCEPTION_REQUIRED, 422,
                    "Phân công cần ngoại lệ được duyệt; dùng luồng xem trước/duyệt ngoại lệ.", details);
        }
        throw PlanException.of(PlanException.HARD_VIOLATIONS, 422, "Phân công vi phạm ràng buộc bắt buộc.", details);
    }
}
