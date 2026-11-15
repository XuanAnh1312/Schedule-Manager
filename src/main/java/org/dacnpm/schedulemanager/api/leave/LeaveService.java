package org.dacnpm.schedulemanager.api.leave;

import org.dacnpm.schedulemanager.api.approvals.ApprovalFlowService;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.events.EventTypes;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentInfo;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.ShiftInfo;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nghỉ phép cơ bản (BA-04).
 *
 * <ul>
 *   <li>Nhân viên tạo đơn theo loại nghỉ Admin cấu hình; loại yêu cầu tài liệu thì bắt buộc đính kèm;
 *       báo trước tối thiểu theo {@code min_notice_days} của loại nghỉ.</li>
 *   <li>Không phân biệt loại nhân viên — Part-time vẫn tạo đơn bình thường.</li>
 *   <li>Trước khi duyệt, Admin xem {@link #impact} để biết ca nào sẽ thiếu người.</li>
 *   <li>Duyệt: hủy các phân công trong khoảng nghỉ qua service lịch (Tường), cùng giao dịch,
 *       rồi phát cảnh báo độ phủ cho Admin nếu có ca thiếu người.</li>
 *   <li>Vắng đột xuất: Admin ghi nhận cho một phân công cụ thể; có báo cáo tỷ lệ vắng.</li>
 * </ul>
 */
@Profile("sqlserver")
@Service
public class LeaveService {

    static final int MAX_TEXT = 1000;
    static final int MAX_SPAN_DAYS = 366;

    private final LeaveRepository repository;
    private final AssignmentMutationService schedule;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final Clock clock;
    private final LeaveBalanceService balances;
    private final ApprovalFlowService flows;

    public LeaveService(LeaveRepository repository, AssignmentMutationService schedule,
                        DomainEventPublisher events, AuditTrail audit, Clock clock, LeaveBalanceService balances,
                        ApprovalFlowService flows) {
        this.repository = repository;
        this.schedule = schedule;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.balances = balances;
        this.flows = flows;
    }

    /** @param halfDay nghỉ nửa ngày (chỉ đơn một ngày) — 0,5 ngày phép (BAO-03) */
    public record CreateLeave(String leaveTypeId, LocalDate startDate, LocalDate endDate, String reason,
                              String documentUrl, Boolean halfDay) {
        public CreateLeave(String leaveTypeId, LocalDate startDate, LocalDate endDate, String reason,
                           String documentUrl) {
            this(leaveTypeId, startDate, endDate, reason, documentUrl, false);
        }
    }

    public record AbsenceInput(String assignmentId, String reason) {}

    public record AbsenceReport(LocalDate from, LocalDate to, long absences, long confirmedAssignments,
                                double absenceRatePercent, long approvedLeaveDays, List<Map<String, Object>> items) {}

    public List<LeaveType> types(boolean includeInactive, CurrentUser me) {
        if (includeInactive) me.requireAdmin();
        return repository.findTypes(!includeInactive);
    }

    @Transactional
    public LeaveView create(CurrentUser me, CreateLeave in) {
        if (me.admin()) {
            throw ApiException.forbidden("Tài khoản Admin không tạo đơn nghỉ trong module này.");
        }
        LeaveType type = repository.findType(in.leaveTypeId())
                .filter(LeaveType::active)
                .orElseThrow(() -> ApiException.badRequest("INVALID_LEAVE_TYPE", "Loại nghỉ không hợp lệ."));
        if (in.startDate() == null || in.endDate() == null) {
            throw ApiException.badRequest("REQUIRED", "Ngày bắt đầu và kết thúc là bắt buộc.");
        }
        if (in.endDate().isBefore(in.startDate())) {
            throw ApiException.badRequest("INVALID_RANGE", "Ngày kết thúc phải sau hoặc bằng ngày bắt đầu.");
        }
        if (ChronoUnit.DAYS.between(in.startDate(), in.endDate()) + 1 > MAX_SPAN_DAYS) {
            throw ApiException.badRequest("INVALID_RANGE", "Một đơn nghỉ tối đa " + MAX_SPAN_DAYS + " ngày.");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate earliest = today.plusDays(type.minNoticeDays());
        if (in.startDate().isBefore(earliest)) {
            throw ApiException.badRequest("NOTICE_TOO_SHORT", type.minNoticeDays() == 0
                    ? "Không thể xin nghỉ cho ngày đã qua. Vắng đột xuất do Admin ghi nhận."
                    : type.name() + " cần báo trước ít nhất " + type.minNoticeDays() + " ngày.");
        }
        String reason = requireText(in.reason(), "Lý do");
        String document = optionalText(in.documentUrl());
        if (type.requiresDocument() && document == null) {
            throw ApiException.badRequest("DOCUMENT_REQUIRED", type.name() + " cần đính kèm tài liệu (đường dẫn).");
        }
        if (repository.overlapsActive(me.id(), in.startDate(), in.endDate())) {
            throw ApiException.conflict("LEAVE_OVERLAP", "Bạn đã có đơn nghỉ chờ duyệt/đã duyệt trùng khoảng ngày này.");
        }
        boolean halfDay = Boolean.TRUE.equals(in.halfDay());
        if (halfDay && !in.startDate().equals(in.endDate())) {
            throw ApiException.badRequest("INVALID_HALF_DAY", "Nghỉ nửa ngày chỉ áp dụng cho đơn một ngày.");
        }
        java.math.BigDecimal days = LeaveBalanceService.total(
                LeaveBalanceService.daysByYear(in.startDate(), in.endDate(), halfDay));
        String id = Ids.next("leave");
        repository.insert(id, me.id(), type.id(), in.startDate(), in.endDate(), reason, document, halfDay, days);
        // BAO-03: giữ chỗ sổ phép trong cùng giao dịch; không đủ phép → 422 và rollback đơn vừa tạo.
        balances.reserve(type, me.id(), id, in.startDate(), in.endDate(), halfDay, me.id());
        // BAO-04: tạo các bước duyệt theo cấu hình; người nhận thông báo đầu tiên là quản lý trực tiếp hoặc Admin.
        List<String> firstApprovers = flows.start(ApprovalFlowService.LEAVE, id, me.id(), type.id());
        LeaveView view = load(id);
        LeaveImpact impact = computeImpact(view);
        audit.record("leave_create", "Gửi đơn nghỉ", describe(view), me.id(), null, snapshot(view), reason);
        events.publish(event(view, EventTypes.LEAVE_REQUESTED, me.id(), firstApprovers,
                "Đơn nghỉ chờ duyệt",
                view.userName() + " xin " + view.leaveTypeName().toLowerCase() + " " + range(view)
                        + (impact.shifts().isEmpty() ? "" : " — ảnh hưởng " + impact.shifts().size() + " ca, "
                        + impact.understaffedAfterApproval() + " ca sẽ thiếu người") + ".",
                "approvals"));
        return view;
    }

    public LeaveImpact impact(CurrentUser me, String id) {
        LeaveView view = load(id);
        requireOwnerOrAdmin(me, view);
        return computeImpact(view);
    }

    /**
     * Duyệt đơn (BAO-04: theo bước). Người duyệt bước hiện tại (quản lý trực tiếp) chỉ hoàn tất bước của mình; lịch và
     * số dư phép chỉ thay đổi ở bước cuối. Admin duyệt ở bước nào cũng là duyệt cuối. Loại nghỉ yêu cầu tài liệu được
     * kiểm tra lại ở mọi bước.
     */
    @Transactional
    public LeaveView approve(CurrentUser me, String id, Integer expectedVersion, String note) {
        LeaveView current = load(id);
        if ("approved".equals(current.status())) {
            return current; // duyệt lặp không hủy ca lần hai
        }
        if (!"pending".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Đơn nghỉ đã được xử lý.");
        }
        checkVersion(current, expectedVersion);
        String cleanNote = optionalText(note);
        LeaveType type = typeOf(current);
        if (type.requiresDocument() && current.documentUrl() == null) {
            throw ApiException.unprocessable("DOCUMENT_REQUIRED",
                    type.name() + " cần tài liệu đính kèm trước khi duyệt.", List.of());
        }
        ApprovalFlowService.Decision decision = flows.approve(me, ApprovalFlowService.LEAVE, id, cleanNote);
        if (decision.outcome() == ApprovalFlowService.Outcome.STEP_APPROVED) {
            audit.record("leave_step_approve", "Duyệt bước " + decision.step().stepNo() + " đơn nghỉ", describe(current),
                    me.id(), snapshot(current), Map.of("leave", snapshot(current), "step", decision.step().stepNo()),
                    cleanNote);
            events.publish(new DomainEvent(EventTypes.LEAVE_STEP_APPROVED + ":" + id + ":s" + decision.step().stepNo(),
                    EventTypes.LEAVE_STEP_APPROVED, "leave_request", id, current.version(), me.id(),
                    decision.nextRecipients(), "Đơn nghỉ chờ duyệt bước tiếp theo",
                    current.userName() + " — " + current.leaveTypeName() + " " + range(current)
                            + ": quản lý đã duyệt, chờ bạn duyệt.", "approvals"));
            return current;
        }
        if (repository.decide(id, current.version(), "approved", me.id(), cleanNote) != 1) {
            LeaveView latest = load(id);
            if ("approved".equals(latest.status())) return latest;
            throw ApiException.conflict("STALE_VERSION", "Đơn nghỉ vừa thay đổi, vui lòng tải lại.");
        }

        // BAO-03: trừ sổ phép (chuyển giữ chỗ thành đã dùng) trong cùng giao dịch.
        balances.consume(type, current, me.id());

        // Cập nhật lịch: gỡ người nghỉ khỏi các ca trong khoảng nghỉ (qua service lịch).
        LeaveImpact impact = computeImpact(current);
        List<String> cancelled = new ArrayList<>();
        for (LeaveImpact.AffectedShift s : impact.shifts()) {
            schedule.cancelAssignment(s.assignmentId(), current.userId(), me.id(), "Nghỉ phép được duyệt (" + id + ")");
            cancelled.add(s.assignmentId());
        }
        repository.setAffectedCount(id, cancelled.size());
        LeaveView updated = load(id);

        audit.record("leave_approve", "Duyệt đơn nghỉ", describe(updated), me.id(),
                snapshot(current), Map.of("leave", snapshot(updated), "cancelledAssignments", cancelled), cleanNote);
        events.publish(event(updated, EventTypes.LEAVE_APPROVED, me.id(), List.of(updated.userId()),
                "Đơn nghỉ đã được duyệt",
                updated.leaveTypeName() + " " + range(updated)
                        + (cancelled.isEmpty() ? "." : ". Bạn đã được gỡ khỏi " + cancelled.size() + " ca trong thời gian nghỉ."),
                "leave"));
        List<String> understaffed = impact.shifts().stream()
                .filter(LeaveImpact.AffectedShift::understaffedAfter)
                .map(s -> s.shiftName() + " " + s.date() + " (" + s.confirmedAfter() + "/" + s.required() + ")")
                .toList();
        if (!understaffed.isEmpty()) {
            events.publish(new DomainEvent(
                    DomainEvent.idOf(EventTypes.COVERAGE_WARNING, updated.id(), updated.version()),
                    EventTypes.COVERAGE_WARNING, "leave_request", updated.id(), updated.version(), me.id(),
                    List.of(DomainEvent.ROLE_ADMIN), "Cảnh báo thiếu người sau khi duyệt nghỉ",
                    "Các ca thiếu người: " + String.join("; ", understaffed) + ". Cân nhắc mở ca trống.",
                    "open-shifts"));
        }
        return updated;
    }

    @Transactional
    public LeaveView reject(CurrentUser me, String id, Integer expectedVersion, String note) {
        LeaveView current = load(id);
        if ("rejected".equals(current.status())) return current;
        if (!"pending".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Đơn nghỉ đã được xử lý.");
        }
        checkVersion(current, expectedVersion);
        String reason = requireText(note, "Lý do từ chối");
        // BAO-04: Admin hoặc người duyệt của bước hiện tại; các bước còn lại bị bỏ qua.
        flows.reject(me, ApprovalFlowService.LEAVE, id, reason);
        if (repository.decide(id, current.version(), "rejected", me.id(), reason) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Đơn nghỉ vừa thay đổi, vui lòng tải lại.");
        }
        balances.release(typeOf(current), current, me.id(), "Đơn nghỉ bị từ chối");
        LeaveView updated = load(id);
        audit.record("leave_reject", "Từ chối đơn nghỉ", describe(updated), me.id(),
                snapshot(current), snapshot(updated), reason);
        events.publish(event(updated, EventTypes.LEAVE_REJECTED, me.id(), List.of(updated.userId()),
                "Đơn nghỉ bị từ chối", updated.leaveTypeName() + " " + range(updated) + ": " + reason, "leave"));
        return updated;
    }

    @Transactional
    public LeaveView cancel(CurrentUser me, String id, Integer expectedVersion) {
        LeaveView current = load(id);
        if (!current.userId().equals(me.id())) {
            throw ApiException.forbidden("Chỉ người tạo đơn mới hủy được.");
        }
        if ("cancelled".equals(current.status())) return current;
        if (!"pending".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE",
                    "Đơn đã được xử lý. Muốn đi làm lại sau khi nghỉ đã duyệt, hãy liên hệ Admin để xếp lại ca.");
        }
        checkVersion(current, expectedVersion);
        if (repository.cancel(id, current.version()) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Đơn nghỉ vừa thay đổi, vui lòng tải lại.");
        }
        balances.release(typeOf(current), current, me.id(), "Nhân viên hủy đơn nghỉ");
        flows.close(ApprovalFlowService.LEAVE, id, me.id(), "Người gửi đã hủy đơn.");
        LeaveView updated = load(id);
        audit.record("leave_cancel", "Hủy đơn nghỉ", describe(updated), me.id(), snapshot(current), snapshot(updated), null);
        events.publish(event(updated, EventTypes.LEAVE_CANCELLED, me.id(), List.of(DomainEvent.ROLE_ADMIN),
                "Đơn nghỉ đã được rút", updated.userName() + " đã hủy đơn " + range(updated) + ".", "approvals"));
        return updated;
    }

    /**
     * BAO-03: Admin hủy đơn đã duyệt khi chưa tới ngày nghỉ → hoàn phép vào sổ. Ca đã gỡ lúc duyệt KHÔNG tự xếp lại
     * (lịch có thể đã có người thay): thông báo yêu cầu Admin xếp lại ca nếu cần.
     */
    @Transactional
    public LeaveView revoke(CurrentUser me, String id, Integer expectedVersion, String note) {
        me.requireAdmin();
        LeaveView current = load(id);
        if ("cancelled".equals(current.status())) return current;
        if (!"approved".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Chỉ hủy được đơn đã duyệt; đơn chờ duyệt hãy từ chối.");
        }
        if (!current.startDate().isAfter(LocalDate.now(clock))) {
            throw ApiException.conflict("LEAVE_STARTED", "Đơn nghỉ đã bắt đầu hoặc đã qua, không hủy được.");
        }
        checkVersion(current, expectedVersion);
        String reason = requireText(note, "Lý do hủy đơn đã duyệt");
        if (repository.revoke(id, current.version(), me.id(), reason) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Đơn nghỉ vừa thay đổi, vui lòng tải lại.");
        }
        balances.restore(typeOf(current), current, me.id(), "Admin hủy đơn đã duyệt: " + reason);
        LeaveView updated = load(id);
        audit.record("leave_revoke", "Hủy đơn nghỉ đã duyệt", describe(updated), me.id(), snapshot(current),
                snapshot(updated), reason);
        events.publish(event(updated, EventTypes.LEAVE_CANCELLED, me.id(), List.of(updated.userId()),
                "Đơn nghỉ đã duyệt bị hủy", updated.leaveTypeName() + " " + range(updated) + ": " + reason
                        + ". Ngày phép đã được hoàn. Liên hệ quản lý để được xếp lại ca nếu cần.", "leave"));
        return updated;
    }

    public LeaveView get(CurrentUser me, String id) {
        LeaveView view = load(id);
        requireOwnerOrAdmin(me, view);
        return view;
    }

    /**
     * scope = mine (mặc định) | all (Admin) | team (đơn của cấp dưới trực tiếp) | approvals (đơn có bước đang chờ tôi
     * duyệt — BAO-04).
     */
    public List<LeaveView> list(CurrentUser me, String scope, String status) {
        String s = status == null || status.isBlank() ? null : status.trim();
        if ("team".equalsIgnoreCase(scope)) {
            return repository.findByManager(me.id(), s, 200);
        }
        if ("approvals".equalsIgnoreCase(scope)) {
            List<LeaveView> result = new ArrayList<>();
            for (String id : flows.pendingFor(me, ApprovalFlowService.LEAVE)) {
                repository.findById(id).filter(v -> "pending".equals(v.status())).ifPresent(result::add);
            }
            return result;
        }
        boolean all = "all".equalsIgnoreCase(scope);
        if (all) me.requireAdmin();
        return repository.find(all ? null : me.id(), s, 200);
    }

    /** Các bước duyệt của một đơn (người xem được đơn mới xem được bước). */
    public List<org.dacnpm.schedulemanager.api.approvals.ApprovalStep> steps(CurrentUser me, String id) {
        LeaveView view = load(id);
        requireOwnerOrAdmin(me, view);
        return flows.steps(ApprovalFlowService.LEAVE, id);
    }

    /** Admin: mọi đơn chờ; quản lý: đơn đang chờ đúng mình duyệt. */
    public long pendingCount(CurrentUser me) {
        if (me.admin()) return repository.countPending();
        return flows.pendingFor(me, ApprovalFlowService.LEAVE).size();
    }

    /* ── Vắng đột xuất ─────────────────────────────────────────────────── */

    @Transactional
    public Map<String, Object> recordAbsence(CurrentUser me, AbsenceInput in) {
        me.requireAdmin();
        String reason = requireText(in.reason(), "Lý do vắng");
        AssignmentInfo a = schedule.findAssignment(in.assignmentId())
                .filter(AssignmentInfo::confirmed)
                .orElseThrow(() -> ApiException.badRequest("INVALID_ASSIGNMENT", "Phân công không tồn tại hoặc đã hủy."));
        if (a.shiftDate().isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest("FUTURE_SHIFT",
                    "Ca chưa diễn ra — nếu nhân viên báo trước, hãy tạo/duyệt đơn nghỉ thay vì ghi vắng.");
        }
        String id = Ids.next("abs");
        try {
            repository.insertAbsence(id, a.userId(), a.shiftId(), a.assignmentId(), reason, me.id());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ABSENCE_EXISTS", "Đã ghi nhận vắng cho nhân viên này ở ca này.");
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("userId", a.userId());
        record.put("shiftId", a.shiftId());
        record.put("assignmentId", a.assignmentId());
        record.put("reason", reason);
        audit.record("absence_record", "Ghi nhận vắng đột xuất", a.label(), me.id(), null, record, reason);
        events.publish(new DomainEvent(DomainEvent.idOf(EventTypes.ABSENCE_RECORDED, id, 0),
                EventTypes.ABSENCE_RECORDED, "absence", id, 0, me.id(), List.of(a.userId()),
                "Ghi nhận vắng mặt", "Bạn được ghi nhận vắng ở " + a.label() + ": " + reason, "my-schedule"));
        return record;
    }

    public AbsenceReport absenceReport(CurrentUser me, LocalDate from, LocalDate to) {
        me.requireAdmin();
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("INVALID_RANGE", "Khoảng ngày không hợp lệ.");
        }
        List<Map<String, Object>> items = repository.findAbsences(from, to);
        long confirmed = repository.countConfirmedAssignments(from, to);
        // Mẫu số = phân công confirmed + vắng (phân công bị vắng vẫn là confirmed) → dùng confirmed.
        double rate = confirmed == 0 ? 0 : Math.round(items.size() * 1000.0 / confirmed) / 10.0;
        return new AbsenceReport(from, to, items.size(), confirmed, rate,
                repository.countApprovedLeaveDays(from, to), items);
    }

    /* ── BAO-04: báo cáo vắng mặt theo quyền ─────────────────────────── */

    /** Một dòng báo cáo; {@code key}/{@code label} theo nhóm (nhân viên, bộ phận, loại nghỉ). */
    public record AbsenceSummaryRow(String key, String label, String department, int employees,
                                    Map<String, java.math.BigDecimal> leaveDaysByType, java.math.BigDecimal leaveDays,
                                    long absences, long assignments, Double absenceRatePercent) {}

    public record AbsenceSummary(LocalDate from, LocalDate to, String groupBy, String scope,
                                 List<AbsenceSummaryRow> rows) {}

    static final java.util.Set<String> GROUPS = java.util.Set.of("employee", "department", "leaveType");

    /**
     * Báo cáo vắng mặt theo nhân viên / bộ phận / loại nghỉ (BAO-04, cung cấp cho Dashboard của Bình). Admin xem toàn công
     * ty; quản lý chỉ cấp dưới trực tiếp; nhân viên thường → 403. Tỷ lệ vắng = vắng đột xuất / phân công confirmed; mẫu
     * số 0 → null (không hiển thị số giả).
     */
    public AbsenceSummary absenceSummary(CurrentUser me, LocalDate from, LocalDate to, String groupBy) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_SPAN_DAYS) {
            throw ApiException.badRequest("INVALID_RANGE", "Khoảng ngày không hợp lệ (tối đa " + MAX_SPAN_DAYS + " ngày).");
        }
        String group = groupBy == null || groupBy.isBlank() ? "employee" : groupBy.trim();
        if (!GROUPS.contains(group)) {
            throw ApiException.badRequest("INVALID_GROUP", "Nhóm theo phải là employee, department hoặc leaveType.");
        }
        String managerScope = null;
        if (!me.admin()) {
            if (!flows.isManager(me)) throw ApiException.forbidden("Chỉ Admin hoặc quản lý trực tiếp xem báo cáo vắng mặt.");
            managerScope = me.id();
        }
        List<Map<String, Object>> people = repository.absenceByEmployee(from, to, managerScope);
        Map<String, Map<String, java.math.BigDecimal>> byUser = new java.util.HashMap<>();
        Map<String, String> typeNames = new java.util.TreeMap<>();
        for (Map<String, Object> r : repository.approvedLeaveDaysByType(from, to, managerScope)) {
            String type = (String) r.get("leaveTypeName");
            typeNames.put((String) r.get("leaveTypeId"), type);
            byUser.computeIfAbsent((String) r.get("userId"), k -> new java.util.TreeMap<>())
                    .merge(type, decimal(r.get("days")), java.math.BigDecimal::add);
        }
        List<AbsenceSummaryRow> rows = new ArrayList<>();
        if ("employee".equals(group)) {
            for (Map<String, Object> p : people) {
                Map<String, java.math.BigDecimal> leave = byUser.getOrDefault((String) p.get("userId"), Map.of());
                rows.add(row((String) p.get("userId"), (String) p.get("fullName"), (String) p.get("departmentName"), 1,
                        leave, number(p.get("absences")), number(p.get("assignments"))));
            }
        } else if ("department".equals(group)) {
            Map<String, List<Map<String, Object>>> byDept = new java.util.TreeMap<>();
            for (Map<String, Object> p : people) {
                String dept = p.get("departmentName") == null ? "(Chưa có bộ phận)" : (String) p.get("departmentName");
                byDept.computeIfAbsent(dept, k -> new ArrayList<>()).add(p);
            }
            for (var e : byDept.entrySet()) {
                Map<String, java.math.BigDecimal> leave = new java.util.TreeMap<>();
                long absences = 0, assignments = 0;
                for (Map<String, Object> p : e.getValue()) {
                    byUser.getOrDefault((String) p.get("userId"), Map.of())
                            .forEach((k, v) -> leave.merge(k, v, java.math.BigDecimal::add));
                    absences += number(p.get("absences"));
                    assignments += number(p.get("assignments"));
                }
                rows.add(row(e.getKey(), e.getKey(), e.getKey(), e.getValue().size(), leave, absences, assignments));
            }
        } else {
            for (var t : typeNames.entrySet()) {
                java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                int employees = 0;
                for (Map<String, java.math.BigDecimal> leave : byUser.values()) {
                    java.math.BigDecimal d = leave.get(t.getValue());
                    if (d != null) {
                        total = total.add(d);
                        employees++;
                    }
                }
                rows.add(new AbsenceSummaryRow(t.getKey(), t.getValue(), null, employees, Map.of(t.getValue(), total),
                        total, 0, 0, null));
            }
        }
        return new AbsenceSummary(from, to, group, me.admin() ? "company" : "direct_reports", rows);
    }

    /** CSV (UTF-8 có BOM để Excel đọc đúng tiếng Việt) của {@link #absenceSummary}. */
    public String absenceSummaryCsv(CurrentUser me, LocalDate from, LocalDate to, String groupBy) {
        AbsenceSummary summary = absenceSummary(me, from, to, groupBy);
        java.util.Set<String> types = new java.util.TreeSet<>();
        summary.rows().forEach(r -> types.addAll(r.leaveDaysByType().keySet()));
        StringBuilder csv = new StringBuilder("\uFEFF");
        List<String> header = new ArrayList<>(List.of("Nhóm", "Bộ phận", "Số nhân viên"));
        types.forEach(t -> header.add("Ngày nghỉ: " + t));
        header.addAll(List.of("Tổng ngày nghỉ đã duyệt", "Vắng đột xuất", "Phân công", "Tỷ lệ vắng (%)"));
        csv.append(csvLine(header));
        for (AbsenceSummaryRow r : summary.rows()) {
            List<String> line = new ArrayList<>(List.of(r.label(), r.department() == null ? "" : r.department(),
                    String.valueOf(r.employees())));
            types.forEach(t -> line.add(r.leaveDaysByType().getOrDefault(t, java.math.BigDecimal.ZERO).toPlainString()));
            line.addAll(List.of(r.leaveDays().toPlainString(), String.valueOf(r.absences()),
                    String.valueOf(r.assignments()), r.absenceRatePercent() == null ? "" : r.absenceRatePercent().toString()));
            csv.append(csvLine(line));
        }
        return csv.toString();
    }

    private static AbsenceSummaryRow row(String key, String label, String dept, int employees,
                                         Map<String, java.math.BigDecimal> leave, long absences, long assignments) {
        java.math.BigDecimal total = leave.values().stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        Double rate = assignments == 0 ? null : Math.round(absences * 1000.0 / assignments) / 10.0;
        return new AbsenceSummaryRow(key, label, dept, employees, leave, total, absences, assignments, rate);
    }

    static String csvLine(List<String> cells) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) b.append(',');
            String c = cells.get(i) == null ? "" : cells.get(i);
            // Chặn công thức khi mở bằng Excel (CSV injection) và thoát dấu ngoặc kép.
            if (!c.isEmpty() && "=+-@".indexOf(c.charAt(0)) >= 0 && !c.matches("-?\\d+(\\.\\d+)?")) c = "'" + c;
            b.append('"').append(c.replace("\"", "\"\"")).append('"');
        }
        return b.append("\r\n").toString();
    }

    private static long number(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static java.math.BigDecimal decimal(Object v) {
        if (v == null) return java.math.BigDecimal.ZERO;
        return v instanceof java.math.BigDecimal d ? d : new java.math.BigDecimal(v.toString());
    }

    /* ── Hỗ trợ ──────────────────────────────────────────────────────── */

    LeaveImpact computeImpact(LeaveView view) {
        List<LeaveImpact.AffectedShift> shifts = new ArrayList<>();
        int understaffed = 0;
        for (AssignmentInfo a : schedule.findConfirmedAssignments(view.userId(), view.startDate(), view.endDate())) {
            ShiftInfo s = schedule.findShift(a.shiftId()).orElse(null);
            if (s == null) continue;
            int after = Math.max(0, s.confirmedCount() - 1);
            boolean under = after < s.totalRequired();
            if (under) understaffed++;
            shifts.add(new LeaveImpact.AffectedShift(a.assignmentId(), a.shiftId(), a.shiftName(), a.shiftDate(),
                    a.startTime() + "–" + a.endTime(), a.published(), s.totalRequired(), s.confirmedCount(),
                    after, under));
        }
        return new LeaveImpact(shifts, understaffed);
    }

    private void requireOwnerOrAdmin(CurrentUser me, LeaveView view) {
        // BAO-04: thêm quản lý trực tiếp và người duyệt của một bước; người khác không thấy (404, không lộ đơn).
        if (!flows.canView(me, ApprovalFlowService.LEAVE, view.id(), view.userId())) {
            throw ApiException.notFound("đơn nghỉ");
        }
    }

    LeaveType typeOf(LeaveView view) {
        return repository.findType(view.leaveTypeId())
                .orElseThrow(() -> ApiException.badRequest("INVALID_LEAVE_TYPE", "Loại nghỉ không còn tồn tại."));
    }

    private LeaveView load(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("đơn nghỉ"));
    }

    private static void checkVersion(LeaveView current, Integer expectedVersion) {
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw ApiException.conflict("STALE_VERSION", "Đơn nghỉ đã thay đổi kể từ lúc bạn xem, vui lòng tải lại.");
        }
    }

    private DomainEvent event(LeaveView v, String type, String actorId, List<String> recipients,
                              String title, String body, String route) {
        return new DomainEvent(DomainEvent.idOf(type, v.id(), v.version()), type, "leave_request", v.id(),
                v.version(), actorId, recipients, title, body, route);
    }

    private static Map<String, Object> snapshot(LeaveView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("userId", v.userId());
        m.put("leaveTypeId", v.leaveTypeId());
        m.put("startDate", v.startDate().toString());
        m.put("endDate", v.endDate().toString());
        m.put("status", v.status());
        m.put("version", v.version());
        return m;
    }

    private static String describe(LeaveView v) {
        return v.userName() + " — " + v.leaveTypeName() + " " + range(v);
    }

    private static String range(LeaveView v) {
        return v.startDate().equals(v.endDate()) ? "ngày " + v.startDate()
                : "từ " + v.startDate() + " đến " + v.endDate();
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("REQUIRED", field + " là bắt buộc.");
        }
        String v = value.trim();
        if (v.length() > MAX_TEXT) throw ApiException.badRequest("TOO_LONG", field + " tối đa " + MAX_TEXT + " ký tự.");
        return v;
    }

    static String optionalText(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        if (v.length() > MAX_TEXT) throw ApiException.badRequest("TOO_LONG", "Tối đa " + MAX_TEXT + " ký tự.");
        return v;
    }
}
