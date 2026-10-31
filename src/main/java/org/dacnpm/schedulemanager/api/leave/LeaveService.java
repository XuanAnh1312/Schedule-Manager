package org.dacnpm.schedulemanager.api.leave;

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

    public LeaveService(LeaveRepository repository, AssignmentMutationService schedule,
                        DomainEventPublisher events, AuditTrail audit, Clock clock) {
        this.repository = repository;
        this.schedule = schedule;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreateLeave(String leaveTypeId, LocalDate startDate, LocalDate endDate, String reason,
                              String documentUrl) {}

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
        String id = Ids.next("leave");
        repository.insert(id, me.id(), type.id(), in.startDate(), in.endDate(), reason, document);
        LeaveView view = load(id);
        LeaveImpact impact = computeImpact(view);
        audit.record("leave_create", "Gửi đơn nghỉ", describe(view), me.id(), null, snapshot(view), reason);
        events.publish(event(view, EventTypes.LEAVE_REQUESTED, me.id(), List.of(DomainEvent.ROLE_ADMIN),
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

    @Transactional
    public LeaveView approve(CurrentUser me, String id, Integer expectedVersion, String note) {
        me.requireAdmin();
        LeaveView current = load(id);
        if ("approved".equals(current.status())) {
            return current; // duyệt lặp không hủy ca lần hai
        }
        if (!"pending".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Đơn nghỉ đã được xử lý.");
        }
        checkVersion(current, expectedVersion);
        String cleanNote = optionalText(note);
        if (repository.decide(id, current.version(), "approved", me.id(), cleanNote) != 1) {
            LeaveView latest = load(id);
            if ("approved".equals(latest.status())) return latest;
            throw ApiException.conflict("STALE_VERSION", "Đơn nghỉ vừa thay đổi, vui lòng tải lại.");
        }

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
        me.requireAdmin();
        LeaveView current = load(id);
        if ("rejected".equals(current.status())) return current;
        if (!"pending".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Đơn nghỉ đã được xử lý.");
        }
        checkVersion(current, expectedVersion);
        String reason = requireText(note, "Lý do từ chối");
        if (repository.decide(id, current.version(), "rejected", me.id(), reason) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Đơn nghỉ vừa thay đổi, vui lòng tải lại.");
        }
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
        LeaveView updated = load(id);
        audit.record("leave_cancel", "Hủy đơn nghỉ", describe(updated), me.id(), snapshot(current), snapshot(updated), null);
        events.publish(event(updated, EventTypes.LEAVE_CANCELLED, me.id(), List.of(DomainEvent.ROLE_ADMIN),
                "Đơn nghỉ đã được rút", updated.userName() + " đã hủy đơn " + range(updated) + ".", "approvals"));
        return updated;
    }

    public LeaveView get(CurrentUser me, String id) {
        LeaveView view = load(id);
        requireOwnerOrAdmin(me, view);
        return view;
    }

    public List<LeaveView> list(CurrentUser me, String scope, String status) {
        boolean all = "all".equalsIgnoreCase(scope);
        if (all) me.requireAdmin();
        String s = status == null || status.isBlank() ? null : status.trim();
        return repository.find(all ? null : me.id(), s, 200);
    }

    public long pendingCount(CurrentUser me) {
        me.requireAdmin();
        return repository.countPending();
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
        if (!me.admin() && !view.userId().equals(me.id())) {
            throw ApiException.notFound("đơn nghỉ");
        }
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
