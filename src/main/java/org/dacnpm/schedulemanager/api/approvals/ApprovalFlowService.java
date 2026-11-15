package org.dacnpm.schedulemanager.api.approvals;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Duyệt nhiều bước theo quản lý trực tiếp (BAO-04) — dùng chung cho đơn nghỉ và yêu cầu đổi/thay ca.
 *
 * <ul>
 *   <li>{@link #start}: tạo các bước theo cấu hình lúc đơn bắt đầu chờ duyệt. {@code admin_only} → không tạo bước, giữ
 *       nguyên hành vi cũ.</li>
 *   <li>{@link #approve}: người duyệt của bước hiện tại duyệt. Trả {@link Outcome#FINAL} khi đây là bước cuối — chỉ khi
 *       đó module gọi mới đổi lịch/số dư. Admin (toàn quyền V1) duyệt ở bước nào cũng là duyệt cuối.</li>
 *   <li>Quản lý chỉ duyệt đơn có bước đang chờ đúng mình (người duyệt chốt lúc tạo) — không duyệt ngoài phạm vi.</li>
 *   <li>Mọi chuyển trạng thái là UPDATE có điều kiện trong giao dịch của bên gọi: hai người duyệt cùng lúc → một bên 409.</li>
 * </ul>
 * Chưa có ma trận role/scope (B0 của Anh) nên chỉ dùng {@code users.manager_id}; không tự định nghĩa role mới.
 */
@Profile("sqlserver")
@Service
public class ApprovalFlowService {

    public static final String LEAVE = "leave";
    public static final String SHIFT_REQUEST = "shift_request";
    static final Set<String> MODES = Set.of(ApprovalFlow.ADMIN_ONLY, ApprovalFlow.MANAGER, ApprovalFlow.MANAGER_THEN_ADMIN);

    public enum Outcome { FINAL, STEP_APPROVED }

    /** @param nextRecipients người cần duyệt tiếp (userId hoặc {@link DomainEvent#ROLE_ADMIN}); rỗng nếu là bước cuối */
    public record Decision(Outcome outcome, ApprovalStep step, List<String> nextRecipients) {}

    public record FlowInput(String subjectType, String leaveTypeId, String mode, Integer expectedVersion) {}

    private final ApprovalRepository repository;
    private final AuditTrail audit;

    public ApprovalFlowService(ApprovalRepository repository, AuditTrail audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /* ── Vòng đời bước duyệt (gọi trong giao dịch của module) ─────────── */

    /**
     * Tạo các bước duyệt cho đơn vừa vào trạng thái chờ duyệt.
     *
     * @return người cần nhận thông báo "chờ duyệt" đầu tiên (quản lý hoặc {@link DomainEvent#ROLE_ADMIN})
     */
    public List<String> start(String subjectType, String subjectId, String requesterId, String leaveTypeId) {
        String mode = repository.findEffectiveFlow(subjectType, leaveTypeId).map(ApprovalFlow::mode)
                .orElse(ApprovalFlow.ADMIN_ONLY);
        if (ApprovalFlow.ADMIN_ONLY.equals(mode)) {
            return List.of(DomainEvent.ROLE_ADMIN);
        }
        Optional<String> manager = repository.activeManagerOf(requesterId).filter(m -> !m.equals(requesterId));
        if (manager.isPresent()) {
            repository.insertStep(subjectType, subjectId, 1, ApprovalStep.MANAGER, manager.get(), "pending", null);
            if (ApprovalFlow.MANAGER_THEN_ADMIN.equals(mode)) {
                repository.insertStep(subjectType, subjectId, 2, ApprovalStep.ADMIN, null, "waiting", null);
            }
            return List.of(manager.get());
        }
        // Không có quản lý trực tiếp đang hoạt động: ghi rõ lý do và chuyển Admin để đơn không bị kẹt.
        repository.insertStep(subjectType, subjectId, 1, ApprovalStep.MANAGER, null, "skipped",
                "Không có quản lý trực tiếp đang hoạt động — chuyển Admin duyệt.");
        repository.insertStep(subjectType, subjectId, 2, ApprovalStep.ADMIN, null, "pending", null);
        return List.of(DomainEvent.ROLE_ADMIN);
    }

    /** Duyệt bước hiện tại. Không có bước (admin_only hoặc đơn cũ) → chỉ Admin, và đó là duyệt cuối. */
    public Decision approve(CurrentUser me, String subjectType, String subjectId, String note) {
        List<ApprovalStep> steps = repository.findSteps(subjectType, subjectId);
        if (steps.stream().noneMatch(ApprovalStep::open)) {
            me.requireAdmin();
            return new Decision(Outcome.FINAL, null, List.of());
        }
        ApprovalStep current = steps.stream().filter(ApprovalStep::pending).findFirst()
                .orElseThrow(() -> ApiException.conflict("STALE_STEP", "Bước duyệt vừa thay đổi, vui lòng tải lại."));
        if (me.admin()) {
            // Admin toàn quyền (V1): duyệt bước hiện tại và bỏ qua các bước còn lại = duyệt cuối.
            transition(current, "approved", me.id(), note);
            repository.skipOpenSteps(subjectType, subjectId, me.id(),
                    ApprovalStep.ADMIN.equals(current.approverKind()) ? "Đã có quyết định cuối."
                            : "Admin duyệt thay các bước còn lại.");
            return new Decision(Outcome.FINAL, current, List.of());
        }
        requireApprover(me, current);
        transition(current, "approved", me.id(), note);
        Optional<ApprovalStep> next = steps.stream()
                .filter(s -> s.stepNo() > current.stepNo() && "waiting".equals(s.status())).findFirst();
        if (next.isEmpty()) {
            return new Decision(Outcome.FINAL, current, List.of());
        }
        if (repository.activateStep(next.get().id()) != 1) {
            throw ApiException.conflict("STALE_STEP", "Bước duyệt vừa thay đổi, vui lòng tải lại.");
        }
        List<String> recipients = next.get().approverId() == null ? List.of(DomainEvent.ROLE_ADMIN)
                : List.of(next.get().approverId());
        return new Decision(Outcome.STEP_APPROVED, current, recipients);
    }

    /** Từ chối ở bước hiện tại (Admin hoặc người duyệt của bước). Các bước còn lại bị bỏ qua. */
    public void reject(CurrentUser me, String subjectType, String subjectId, String reason) {
        List<ApprovalStep> steps = repository.findSteps(subjectType, subjectId);
        Optional<ApprovalStep> current = steps.stream().filter(ApprovalStep::pending).findFirst();
        if (current.isEmpty()) {
            me.requireAdmin();
            return;
        }
        if (!me.admin()) requireApprover(me, current.get());
        transition(current.get(), "rejected", me.id(), reason);
        repository.skipOpenSteps(subjectType, subjectId, me.id(), "Đã bị từ chối ở bước " + current.get().stepNo() + ".");
    }

    /** Người gửi hủy đơn: đóng mọi bước còn mở. */
    public void close(String subjectType, String subjectId, String actorId, String note) {
        repository.skipOpenSteps(subjectType, subjectId, actorId, note);
    }

    /** Người được xem đơn ngoài chủ đơn và Admin: quản lý trực tiếp, hoặc người duyệt của một bước. */
    public boolean canView(CurrentUser me, String subjectType, String subjectId, String requesterId) {
        if (me.admin() || me.id().equals(requesterId)) return true;
        if (repository.isManagerOf(me.id(), requesterId)) return true;
        return repository.findSteps(subjectType, subjectId).stream().anyMatch(s -> me.id().equals(s.approverId()));
    }

    /** Người dùng có thể duyệt bước đang chờ của đơn này không (để UI hiện nút). */
    public boolean canDecide(CurrentUser me, String subjectType, String subjectId) {
        List<ApprovalStep> steps = repository.findSteps(subjectType, subjectId);
        if (steps.stream().noneMatch(ApprovalStep::open)) return me.admin();
        return me.admin() || steps.stream().anyMatch(s -> s.pending() && me.id().equals(s.approverId()));
    }

    public List<ApprovalStep> steps(String subjectType, String subjectId) {
        return repository.findSteps(subjectType, subjectId);
    }

    public List<String> pendingFor(CurrentUser me, String subjectType) {
        return repository.pendingSubjectsFor(me.id(), subjectType);
    }

    public boolean isManager(CurrentUser me) {
        return repository.hasReports(me.id());
    }

    public boolean isManagerOf(CurrentUser me, String userId) {
        return repository.isManagerOf(me.id(), userId);
    }

    /* ── Cấu hình (Admin) ─────────────────────────────────────────────── */

    public List<ApprovalFlow> flows(CurrentUser me) {
        me.requireAdmin();
        return repository.findFlows();
    }

    /** Đặt quy trình cho (đối tượng, loại nghỉ). Chỉ áp dụng cho đơn bắt đầu chờ duyệt sau thời điểm đổi. */
    @Transactional
    public ApprovalFlow setFlow(CurrentUser me, FlowInput in) {
        me.requireAdmin();
        String subject = in.subjectType() == null ? null : in.subjectType().trim();
        if (!LEAVE.equals(subject) && !SHIFT_REQUEST.equals(subject)) {
            throw ApiException.badRequest("INVALID_SUBJECT", "Đối tượng phải là 'leave' hoặc 'shift_request'.");
        }
        if (in.mode() == null || !MODES.contains(in.mode())) {
            throw ApiException.badRequest("INVALID_MODE", "Quy trình phải là admin_only, manager hoặc manager_then_admin.");
        }
        String leaveType = in.leaveTypeId() == null || in.leaveTypeId().isBlank() ? null : in.leaveTypeId().trim();
        if (SHIFT_REQUEST.equals(subject) && leaveType != null) {
            throw ApiException.badRequest("INVALID_SCOPE", "Yêu cầu đổi ca không cấu hình theo loại nghỉ.");
        }
        Optional<ApprovalFlow> existing = repository.findFlow(subject, leaveType);
        if (existing.isEmpty()) {
            repository.insertFlow(subject, leaveType, in.mode(), me.id());
        } else {
            int expected = in.expectedVersion() == null ? existing.get().version() : in.expectedVersion();
            if (repository.updateFlow(existing.get().id(), expected, in.mode(), me.id()) != 1) {
                throw ApiException.conflict("STALE_VERSION", "Cấu hình vừa thay đổi, vui lòng tải lại.");
            }
        }
        ApprovalFlow after = repository.findFlow(subject, leaveType).orElseThrow();
        audit.record("approval_flow_set", "Đổi quy trình duyệt", subject + (leaveType == null ? "" : " · " + leaveType)
                + ": " + in.mode(), me.id(), existing.map(f -> Map.of("mode", f.mode())).orElse(null),
                Map.of("mode", after.mode()), null);
        return after;
    }

    /* ── Hỗ trợ ───────────────────────────────────────────────────────── */

    private void requireApprover(CurrentUser me, ApprovalStep step) {
        if (!ApprovalStep.MANAGER.equals(step.approverKind()) || !me.id().equals(step.approverId())) {
            throw ApiException.forbidden(ApprovalStep.ADMIN.equals(step.approverKind())
                    ? "Bước này chờ Admin duyệt." : "Bạn không phải người duyệt của bước hiện tại.");
        }
    }

    private void transition(ApprovalStep step, String to, String actorId, String note) {
        if (repository.decideStep(step.id(), step.version(), "pending", to, actorId, note) != 1) {
            throw ApiException.conflict("STALE_STEP", "Bước duyệt vừa được người khác xử lý, vui lòng tải lại.");
        }
    }
}
