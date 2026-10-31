package org.dacnpm.schedulemanager.api.requests;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.dacnpm.schedulemanager.common.UserDirectory;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.events.EventTypes;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentInfo;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentValidationService;
import org.dacnpm.schedulemanager.domain.schedule.Violation;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.dacnpm.schedulemanager.api.requests.ShiftRequestStatus.*;

/**
 * Đổi ca / nhờ làm thay (BA-01) và duyệt an toàn (BA-02).
 *
 * <h3>Luồng</h3>
 * <pre>
 * Nhân viên A gửi ──► pending_peer ──(B đồng ý)──► pending_approval ──(Admin duyệt)──► approved
 *                         │  (B từ chối) ► peer_declined         │  (Admin từ chối) ► rejected
 *                         └─ A hủy ► cancelled ◄──────────────────┘
 * </pre>
 *
 * <h3>Bảo đảm</h3>
 * <ul>
 *   <li><b>Lịch chính thức không đổi khi chưa duyệt</b> — chỉ {@link #approve} gọi
 *       {@link AssignmentMutationService} (AC-08).</li>
 *   <li><b>Kiểm tra lại khi duyệt</b>: chủ sở hữu phân công, ca còn ở tương lai, và toàn bộ
 *       ràng buộc qua {@link AssignmentValidationService} tại thời điểm duyệt.</li>
 *   <li><b>Duyệt lặp không đổi lặp</b>: chuyển trạng thái bằng UPDATE có điều kiện
 *       (version + status). Lần duyệt thứ hai thấy đã {@code approved} → trả kết quả cũ.</li>
 *   <li>Lịch, trạng thái, audit, outbox nằm trong <b>một giao dịch</b>; lỗi ở bất kỳ bước nào → rollback hết.</li>
 * </ul>
 */
@Profile("sqlserver")
@Service
public class ShiftRequestService {

    static final int MAX_REASON = 1000;
    static final int MAX_LIST = 200;

    private final ShiftRequestRepository repository;
    private final AssignmentMutationService schedule;
    private final AssignmentValidationService validator;
    private final UserDirectory users;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final Clock clock;

    public ShiftRequestService(ShiftRequestRepository repository,
                               AssignmentMutationService schedule,
                               AssignmentValidationService validator,
                               UserDirectory users,
                               DomainEventPublisher events,
                               AuditTrail audit,
                               Clock clock) {
        this.repository = repository;
        this.schedule = schedule;
        this.validator = validator;
        this.users = users;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreateRequest(String type, String sourceAssignmentId, String targetUserId,
                                String targetAssignmentId, String reason) {}

    public record Candidate(String userId, String fullName, boolean eligible, List<String> reasons) {}

    /* ── Gửi yêu cầu ─────────────────────────────────────────────────────── */

    @Transactional
    public ShiftRequestView create(CurrentUser me, CreateRequest in) {
        if (me.admin()) {
            throw ApiException.forbidden("Admin chỉnh lịch trực tiếp, không gửi yêu cầu đổi ca.");
        }
        String type = in.type() == null ? null : in.type().trim().toLowerCase();
        if (!TYPE_SWAP.equals(type) && !TYPE_COVER.equals(type)) {
            throw ApiException.badRequest("INVALID_TYPE", "Loại yêu cầu phải là 'swap' (đổi ca) hoặc 'cover' (làm thay).");
        }
        String reason = requireText(in.reason(), "Lý do");
        if (in.targetUserId() == null || in.targetUserId().equals(me.id())) {
            throw ApiException.badRequest("INVALID_TARGET", "Chọn một đồng nghiệp khác để đổi/làm thay.");
        }
        UserDirectory.Employee target = users.find(in.targetUserId())
                .filter(UserDirectory.Employee::activeEmployee)
                .orElseThrow(() -> ApiException.badRequest("INVALID_TARGET", "Người nhận không tồn tại hoặc đã bị khóa."));

        AssignmentInfo source = requireOwnFutureAssignment(in.sourceAssignmentId(), me.id(), "Ca của bạn");
        AssignmentInfo targetAssignment = null;
        if (TYPE_SWAP.equals(type)) {
            targetAssignment = requireOwnFutureAssignment(in.targetAssignmentId(), target.id(), "Ca của người nhận");
            if (targetAssignment.shiftId().equals(source.shiftId())) {
                throw ApiException.badRequest("SAME_SHIFT", "Hai người đang cùng một ca, không cần đổi.");
            }
        } else if (in.targetAssignmentId() != null) {
            throw ApiException.badRequest("INVALID_TARGET", "Yêu cầu làm thay không kèm ca của người nhận.");
        }

        // Kiểm tra sớm để báo ngay cho người gửi; vẫn kiểm tra lại khi duyệt.
        List<String> blocking = blockingViolations(type, me.id(), target.id(), source, targetAssignment);
        if (!blocking.isEmpty()) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "Yêu cầu vi phạm ràng buộc lịch.", blocking);
        }

        String id = Ids.next("sreq");
        try {
            repository.insert(id, type, me.id(), source.assignmentId(), source.shiftId(), target.id(),
                    targetAssignment == null ? null : targetAssignment.assignmentId(),
                    targetAssignment == null ? null : targetAssignment.shiftId(), reason);
            repository.captureScheduleSnapshot(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("REQUEST_ALREADY_OPEN", "Ca này đã có một yêu cầu đang chờ xử lý.");
        }
        ShiftRequestView view = load(id);
        audit.record("shift_request_create", "Gửi yêu cầu " + typeLabel(type), describe(view), me.id(),
                null, snapshot(view), reason);
        events.publish(event(view, EventTypes.SHIFT_REQUEST_CREATED, me.id(), List.of(target.id()),
                "Yêu cầu " + typeLabel(type) + " mới",
                view.requesterName() + " đề nghị bạn " + (TYPE_SWAP.equals(type) ? "đổi ca: " : "làm thay ca ")
                        + label(view.sourceShift()) + ".", "my-requests"));
        return view;
    }

    /* ── Người nhận phản hồi ─────────────────────────────────────────────── */

    @Transactional
    public ShiftRequestView peerRespond(CurrentUser me, String id, boolean accept, Integer expectedVersion, String note) {
        ShiftRequestView current = load(id);
        if (!current.targetUserId().equals(me.id())) {
            throw ApiException.forbidden("Chỉ người được đề nghị mới phản hồi yêu cầu này.");
        }
        if (!PENDING_PEER.equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Yêu cầu không còn chờ bạn phản hồi.");
        }
        checkVersion(current, expectedVersion);
        String cleanNote = optionalText(note);
        if (accept) {
            requireScheduleUnchanged(id);
            List<String> blocking = blockingViolations(current);
            if (!blocking.isEmpty()) {
                throw ApiException.unprocessable("VALIDATION_FAILED",
                        "Bạn không thể nhận ca này do vi phạm ràng buộc lịch.", blocking);
            }
        }
        String next = accept ? PENDING_APPROVAL : PEER_DECLINED;
        if (repository.peerRespond(id, current.version(), next, cleanNote) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Yêu cầu vừa thay đổi, vui lòng tải lại.");
        }
        ShiftRequestView updated = load(id);
        audit.record("shift_request_peer_" + (accept ? "accept" : "decline"),
                accept ? "Đồng ý đổi/làm thay ca" : "Từ chối đổi/làm thay ca", describe(updated), me.id(),
                snapshot(current), snapshot(updated), cleanNote);
        if (accept) {
            events.publish(event(updated, EventTypes.SHIFT_REQUEST_PEER_ACCEPTED, me.id(),
                    List.of(DomainEvent.ROLE_ADMIN, updated.requesterId()),
                    "Yêu cầu " + typeLabel(updated.type()) + " chờ duyệt",
                    updated.targetUserName() + " đã đồng ý " + typeLabel(updated.type()) + " với "
                            + updated.requesterName() + " (" + label(updated.sourceShift()) + ").",
                    "approvals"));
        } else {
            events.publish(event(updated, EventTypes.SHIFT_REQUEST_PEER_DECLINED, me.id(),
                    List.of(updated.requesterId()), "Yêu cầu bị từ chối",
                    updated.targetUserName() + " đã từ chối " + typeLabel(updated.type()) + " "
                            + label(updated.sourceShift()) + ".", "my-requests"));
        }
        return updated;
    }

    /* ── Duyệt ───────────────────────────────────────────────────────────── */

    @Transactional
    public ShiftRequestView approve(CurrentUser me, String id, Integer expectedVersion, String note) {
        me.requireAdmin();
        ShiftRequestView current = load(id);
        if (APPROVED.equals(current.status())) {
            return current; // duyệt lặp: không thay đổi gì thêm
        }
        if (!PENDING_APPROVAL.equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Chỉ duyệt được yêu cầu mà người nhận đã đồng ý.");
        }
        checkVersion(current, expectedVersion);

        // Kiểm tra lại tại thời điểm duyệt: lịch có thể đã đổi từ lúc gửi.
        requireScheduleUnchanged(id);
        AssignmentInfo source = requireOwnFutureAssignment(current.sourceAssignmentId(), current.requesterId(),
                "Ca gốc");
        AssignmentInfo targetAssignment = TYPE_SWAP.equals(current.type())
                ? requireOwnFutureAssignment(current.targetAssignmentId(), current.targetUserId(), "Ca của người nhận")
                : null;
        List<String> blocking = blockingViolations(current.type(), current.requesterId(), current.targetUserId(),
                source, targetAssignment);
        if (!blocking.isEmpty()) {
            throw ApiException.unprocessable("VALIDATION_FAILED",
                    "Không thể duyệt: lịch hiện tại vi phạm ràng buộc.", blocking);
        }

        String cleanNote = optionalText(note);
        int changed = repository.decide(id, current.version(), List.of(PENDING_APPROVAL), APPROVED, me.id(),
                cleanNote, current.targetUserId());
        if (changed != 1) {
            ShiftRequestView latest = load(id);
            if (APPROVED.equals(latest.status())) {
                return latest; // một người duyệt khác vừa duyệt xong — không áp dụng lần hai
            }
            throw ApiException.conflict("STALE_VERSION", "Yêu cầu vừa thay đổi, vui lòng tải lại.");
        }

        String auditReason = "Duyệt yêu cầu " + id;
        if (targetAssignment != null) {
            // Remove both old assignments before validating either replacement. The surrounding
            // transaction rolls back both removals if either new assignment is invalid.
            schedule.cancelAssignment(source.assignmentId(), current.requesterId(), me.id(), auditReason);
            schedule.cancelAssignment(targetAssignment.assignmentId(), current.targetUserId(), me.id(), auditReason);
            schedule.addAssignment(source.shiftId(), current.targetUserId(), me.id(), auditReason);
            schedule.addAssignment(targetAssignment.shiftId(), current.requesterId(), me.id(), auditReason);
        } else {
            schedule.reassign(source.assignmentId(), current.requesterId(), current.targetUserId(), me.id(), auditReason);
        }

        ShiftRequestView updated = load(id);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put(source.assignmentId(), current.requesterId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put(source.assignmentId(), current.targetUserId());
        if (targetAssignment != null) {
            before.put(targetAssignment.assignmentId(), current.targetUserId());
            after.put(targetAssignment.assignmentId(), current.requesterId());
        }
        audit.record("shift_request_approve", "Duyệt " + typeLabel(current.type()), describe(updated), me.id(),
                Map.of("request", snapshot(current), "assignments", before),
                Map.of("request", snapshot(updated), "assignments", after), cleanNote);
        events.publish(event(updated, EventTypes.SHIFT_REQUEST_APPROVED, me.id(),
                List.of(updated.requesterId(), updated.targetUserId()),
                "Đã duyệt " + typeLabel(updated.type()),
                "Lịch đã cập nhật: " + updated.targetUserName() + " phụ trách " + label(updated.sourceShift())
                        + (updated.targetShift() == null ? "" : "; " + updated.requesterName() + " phụ trách "
                        + label(updated.targetShift())) + ".", "my-schedule"));
        return updated;
    }

    @Transactional
    public ShiftRequestView reject(CurrentUser me, String id, Integer expectedVersion, String note) {
        me.requireAdmin();
        ShiftRequestView current = load(id);
        if (REJECTED.equals(current.status())) {
            return current;
        }
        if (!current.isOpen()) {
            throw ApiException.conflict("INVALID_STATE", "Yêu cầu đã được xử lý.");
        }
        checkVersion(current, expectedVersion);
        String reason = requireText(note, "Lý do từ chối");
        if (repository.decide(id, current.version(), List.of(PENDING_PEER, PENDING_APPROVAL), REJECTED, me.id(),
                reason, null) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Yêu cầu vừa thay đổi, vui lòng tải lại.");
        }
        ShiftRequestView updated = load(id);
        audit.record("shift_request_reject", "Từ chối " + typeLabel(current.type()), describe(updated), me.id(),
                snapshot(current), snapshot(updated), reason);
        List<String> notify = new ArrayList<>(List.of(updated.requesterId()));
        if (PENDING_APPROVAL.equals(current.status())) notify.add(updated.targetUserId());
        events.publish(event(updated, EventTypes.SHIFT_REQUEST_REJECTED, me.id(), notify,
                "Yêu cầu " + typeLabel(updated.type()) + " bị từ chối",
                label(updated.sourceShift()) + ": " + reason, "my-requests"));
        return updated;
    }

    @Transactional
    public ShiftRequestView cancel(CurrentUser me, String id, Integer expectedVersion) {
        ShiftRequestView current = load(id);
        if (!current.requesterId().equals(me.id())) {
            throw ApiException.forbidden("Chỉ người gửi mới hủy được yêu cầu.");
        }
        if (CANCELLED.equals(current.status())) {
            return current;
        }
        if (!current.isOpen()) {
            throw ApiException.conflict("INVALID_STATE", "Yêu cầu đã được xử lý, không thể hủy.");
        }
        checkVersion(current, expectedVersion);
        if (repository.cancel(id, current.version()) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Yêu cầu vừa thay đổi, vui lòng tải lại.");
        }
        ShiftRequestView updated = load(id);
        audit.record("shift_request_cancel", "Hủy yêu cầu " + typeLabel(current.type()), describe(updated), me.id(),
                snapshot(current), snapshot(updated), null);
        List<String> notify = new ArrayList<>(List.of(updated.targetUserId()));
        if (PENDING_APPROVAL.equals(current.status())) notify.add(DomainEvent.ROLE_ADMIN);
        events.publish(event(updated, EventTypes.SHIFT_REQUEST_CANCELLED, me.id(), notify,
                "Yêu cầu đã bị hủy",
                updated.requesterName() + " đã hủy yêu cầu " + typeLabel(updated.type()) + " "
                        + label(updated.sourceShift()) + ".", "my-requests"));
        return updated;
    }

    /* ── Đọc ─────────────────────────────────────────────────────────────── */

    public ShiftRequestView get(CurrentUser me, String id) {
        ShiftRequestView view = load(id);
        if (!me.admin() && !view.requesterId().equals(me.id()) && !view.targetUserId().equals(me.id())) {
            throw ApiException.notFound("yêu cầu"); // không lộ yêu cầu của người khác
        }
        return view;
    }

    /** scope = mine (mặc định) | all (chỉ admin). */
    public List<ShiftRequestView> list(CurrentUser me, String scope, String status) {
        boolean all = "all".equalsIgnoreCase(scope);
        if (all) me.requireAdmin();
        return repository.find(all ? null : me.id(), blankToNull(status), MAX_LIST);
    }

    /** Gợi ý đồng nghiệp có thể làm thay một ca của tôi (kèm lý do không đủ điều kiện). */
    public List<Candidate> coverCandidates(CurrentUser me, String assignmentId) {
        AssignmentInfo source = requireOwnFutureAssignment(assignmentId, me.id(), "Ca của bạn");
        List<Candidate> result = new ArrayList<>();
        for (UserDirectory.Employee e : users.activeEmployees()) {
            if (e.id().equals(me.id())) continue;
            List<String> reasons = blockingMessages(validator.validate(e.id(), source.shiftId(), me.id(), Set.of(), null));
            result.add(new Candidate(e.id(), e.fullName(), reasons.isEmpty(), reasons));
        }
        result.sort((a, b) -> Boolean.compare(b.eligible(), a.eligible()));
        return result;
    }

    /** Các ca tương lai đã công bố của đồng nghiệp để chọn đổi chéo. */
    public List<AssignmentInfo> swapOptions(CurrentUser me, String targetUserId) {
        if (targetUserId == null || targetUserId.equals(me.id())) {
            throw ApiException.badRequest("INVALID_TARGET", "Chọn một đồng nghiệp khác.");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        return schedule.findConfirmedAssignments(targetUserId, today, today.plusDays(62)).stream()
                .filter(AssignmentInfo::published)
                .filter(a -> a.startsAt().isAfter(now))
                .toList();
    }

    public Map<String, Long> pendingCounts(CurrentUser me) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("incomingPeer", repository.countIncomingForPeer(me.id()));
        if (me.admin()) {
            counts.put("awaitingApproval", repository.countByStatus(PENDING_APPROVAL));
        }
        return counts;
    }

    /* ── Hỗ trợ ──────────────────────────────────────────────────────────── */

    private AssignmentInfo requireOwnFutureAssignment(String assignmentId, String ownerId, String what) {
        if (assignmentId == null) {
            throw ApiException.badRequest("REQUIRED", what + " là bắt buộc.");
        }
        AssignmentInfo a = schedule.findAssignment(assignmentId)
                .orElseThrow(() -> ApiException.badRequest("INVALID_ASSIGNMENT", what + " không tồn tại."));
        if (!a.confirmed() || !ownerId.equals(a.userId())) {
            throw ApiException.conflict("SCHEDULE_CHANGED", what + " không còn thuộc người này (lịch đã thay đổi).");
        }
        if (!a.published()) {
            throw ApiException.badRequest("SHIFT_NOT_PUBLISHED", what + " chưa thuộc lịch đã công bố.");
        }
        if (!a.startsAt().isAfter(LocalDateTime.now(clock))) {
            throw ApiException.badRequest("SHIFT_STARTED", what + " đã bắt đầu hoặc đã qua.");
        }
        return a;
    }

    private List<String> blockingViolations(ShiftRequestView r) {
        AssignmentInfo source = requireOwnFutureAssignment(r.sourceAssignmentId(), r.requesterId(), "Ca gốc");
        AssignmentInfo target = TYPE_SWAP.equals(r.type())
                ? requireOwnFutureAssignment(r.targetAssignmentId(), r.targetUserId(), "Ca của người nhận") : null;
        return blockingViolations(r.type(), r.requesterId(), r.targetUserId(), source, target);
    }

    private List<String> blockingViolations(String type, String requesterId, String targetUserId,
                                            AssignmentInfo source, AssignmentInfo targetAssignment) {
        List<String> result = new ArrayList<>();
        // Người nhận lấy ca gốc; khi đổi chéo, ca của chính họ sẽ được gỡ nên bỏ qua khi kiểm tra.
        Set<String> targetIgnores = targetAssignment == null ? Set.of() : Set.of(targetAssignment.assignmentId());
        for (String m : blockingMessages(validator.validate(targetUserId, source.shiftId(), requesterId,
                targetIgnores, null))) {
            result.add("Người nhận: " + m);
        }
        if (TYPE_SWAP.equals(type) && targetAssignment != null) {
            for (String m : blockingMessages(validator.validate(requesterId, targetAssignment.shiftId(), targetUserId,
                    Set.of(source.assignmentId()), null))) {
                result.add("Người gửi: " + m);
            }
        }
        return result;
    }

    private static List<String> blockingMessages(List<Violation> violations) {
        return violations.stream().filter(Violation::blocking).map(Violation::message).toList();
    }

    private ShiftRequestView load(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("yêu cầu"));
    }

    private void requireScheduleUnchanged(String id) {
        repository.scheduleUnchanged(id).filter(unchanged->!unchanged).ifPresent(ignored->{
            throw ApiException.conflict("SCHEDULE_CHANGED","Lịch đã thay đổi từ khi gửi yêu cầu. Vui lòng hủy và tạo yêu cầu mới.");
        });
    }

    private static void checkVersion(ShiftRequestView current, Integer expectedVersion) {
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw ApiException.conflict("STALE_VERSION", "Yêu cầu đã thay đổi kể từ lúc bạn xem, vui lòng tải lại.");
        }
    }

    private DomainEvent event(ShiftRequestView v, String type, String actorId, List<String> recipients,
                              String title, String body, String route) {
        return new DomainEvent(DomainEvent.idOf(type, v.id(), v.version()), type, "shift_request", v.id(),
                v.version(), actorId, recipients, title, body, route);
    }

    private static Map<String, Object> snapshot(ShiftRequestView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("type", v.type());
        m.put("status", v.status());
        m.put("requesterId", v.requesterId());
        m.put("targetUserId", v.targetUserId());
        m.put("sourceAssignmentId", v.sourceAssignmentId());
        m.put("targetAssignmentId", v.targetAssignmentId());
        m.put("finalResponsibleUserId", v.finalResponsibleUserId());
        m.put("version", v.version());
        return m;
    }

    private static String describe(ShiftRequestView v) {
        return v.requesterName() + " → " + v.targetUserName() + ": " + label(v.sourceShift())
                + (v.targetShift() == null ? "" : " ⇄ " + label(v.targetShift()));
    }

    static String label(ShiftSummary s) {
        if (s == null) return "";
        return s.name() + " " + s.date() + " " + s.startTime() + "–" + s.endTime();
    }

    static String typeLabel(String type) {
        return TYPE_SWAP.equals(type) ? "đổi ca" : "làm thay ca";
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("REQUIRED", field + " là bắt buộc.");
        }
        String v = value.trim();
        if (v.length() > MAX_REASON) {
            throw ApiException.badRequest("TOO_LONG", field + " tối đa " + MAX_REASON + " ký tự.");
        }
        return v;
    }

    static String optionalText(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        if (v.length() > MAX_REASON) {
            throw ApiException.badRequest("TOO_LONG", "Ghi chú tối đa " + MAX_REASON + " ký tự.");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
