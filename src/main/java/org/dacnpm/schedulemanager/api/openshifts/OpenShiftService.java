package org.dacnpm.schedulemanager.api.openshifts;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.dacnpm.schedulemanager.common.UserDirectory;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.events.EventTypes;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentMutationService;
import org.dacnpm.schedulemanager.domain.schedule.AssignmentValidationService;
import org.dacnpm.schedulemanager.domain.schedule.ShiftInfo;
import org.dacnpm.schedulemanager.domain.schedule.Violation;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ca trống (Open Shift — BA-03).
 *
 * <ul>
 *   <li>Admin công bố ca trống cho một ca đã công bố còn thiếu người: số chỗ ≤ số người còn thiếu,
 *       điều kiện Level (tùy chọn), hạn nhận ca ≤ giờ bắt đầu ca.</li>
 *   <li>Nhân viên đủ điều kiện đăng ký; kiểm tra ràng buộc lịch ngay khi đăng ký.</li>
 *   <li>Admin chọn người: kiểm tra lại ràng buộc, rồi <b>giữ chỗ nguyên tử</b> (UPDATE có điều kiện
 *       {@code filled_count < slots}) → không bao giờ xác nhận vượt số lượng, kể cả hai Admin duyệt đồng thời.</li>
 *   <li>Đủ người → các đăng ký còn chờ tự chuyển "từ chối" kèm lý do và được thông báo.</li>
 * </ul>
 */
@Profile("sqlserver")
@Service
public class OpenShiftService {

    private final OpenShiftRepository repository;
    private final AssignmentMutationService schedule;
    private final AssignmentValidationService validator;
    private final UserDirectory users;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OpenShiftService(OpenShiftRepository repository, AssignmentMutationService schedule,
                            AssignmentValidationService validator, UserDirectory users,
                            DomainEventPublisher events, AuditTrail audit, JdbcTemplate jdbc, Clock clock) {
        this.repository = repository;
        this.schedule = schedule;
        this.validator = validator;
        this.users = users;
        this.events = events;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param claimDeadline hạn nhận ca theo giờ địa phương doanh nghiệp (ví dụ 2026-11-02T18:00)
     */
    public record CreateOpenShift(String shiftId, Integer slots, String requiredLevelId,
                                  LocalDateTime claimDeadline, String note) {}

    public record Decision(Integer expectedVersion, String note) {}

    @Transactional
    public OpenShiftView create(CurrentUser me, CreateOpenShift in) {
        me.requireAdmin();
        ShiftInfo shift = schedule.findShift(in.shiftId())
                .orElseThrow(() -> ApiException.badRequest("INVALID_SHIFT", "Ca không tồn tại."));
        if (!"published".equals(shift.status())) {
            throw ApiException.badRequest("SHIFT_NOT_PUBLISHED", "Chỉ mở ca trống cho ca đã công bố.");
        }
        LocalDateTime nowLocal = LocalDateTime.now(clock);
        if (!shift.startsAt().isAfter(nowLocal)) {
            throw ApiException.badRequest("SHIFT_STARTED", "Ca đã bắt đầu hoặc đã qua.");
        }
        int slots = in.slots() == null ? shift.missing() : in.slots();
        if (slots < 1) {
            throw ApiException.badRequest("NO_VACANCY", "Ca đã đủ người, không cần mở ca trống.");
        }
        if (slots > shift.missing()) {
            throw ApiException.badRequest("TOO_MANY_SLOTS",
                    "Số chỗ (" + slots + ") vượt số người còn thiếu (" + shift.missing() + ").");
        }
        if (in.claimDeadline() == null || !in.claimDeadline().isAfter(nowLocal)) {
            throw ApiException.badRequest("INVALID_DEADLINE", "Hạn nhận ca phải ở tương lai.");
        }
        if (in.claimDeadline().isAfter(shift.startsAt())) {
            throw ApiException.badRequest("INVALID_DEADLINE", "Hạn nhận ca không được sau giờ bắt đầu ca.");
        }
        String levelId = in.requiredLevelId() == null || in.requiredLevelId().isBlank() ? null : in.requiredLevelId();
        if (levelId != null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM levels WHERE id = ?", Integer.class, levelId);
            if (n == null || n == 0) throw ApiException.badRequest("INVALID_LEVEL", "Level không tồn tại.");
        }
        if (repository.hasOpenForShift(shift.shiftId())) {
            throw ApiException.conflict("OPEN_SHIFT_EXISTS", "Ca này đã có một ca trống đang mở.");
        }
        String note = optional(in.note());
        String id = Ids.next("open");
        repository.insert(id, shift.shiftId(), slots, levelId, toUtc(in.claimDeadline()), note, me.id());
        OpenShiftView view = load(id, me.id());

        audit.record("open_shift_create", "Mở ca trống", shift.label() + " — " + slots + " chỗ", me.id(),
                null, Map.of("openShiftId", id, "shiftId", shift.shiftId(), "slots", slots), note);
        // Thông báo cho nhân viên có Level phù hợp (ràng buộc chi tiết kiểm tra lúc đăng ký/duyệt).
        List<String> recipients = new ArrayList<>();
        for (UserDirectory.Employee e : users.activeEmployees()) {
            if (levelId == null || levelId.equals(e.levelId()) || canSubstitute(e.levelId(), levelId)) {
                recipients.add(e.id());
            }
        }
        events.publish(new DomainEvent(DomainEvent.idOf(EventTypes.OPEN_SHIFT_PUBLISHED, id, view.version()),
                EventTypes.OPEN_SHIFT_PUBLISHED, "open_shift", id, view.version(), me.id(), recipients,
                "Có ca trống mới", shift.label() + " cần " + slots + " người. Hạn nhận: "
                + in.claimDeadline().toString().replace('T', ' ') + ".", "open-shifts"));
        return view;
    }

    public List<OpenShiftView> list(CurrentUser me, String status) {
        if (me.admin()) {
            return repository.findAll(status == null || status.isBlank() ? null : status, me.id());
        }
        return repository.findVisibleTo(me.id());
    }

    public Map<String, Object> detail(CurrentUser me, String id) {
        OpenShiftView view = load(id, me.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openShift", view);
        if (me.admin()) {
            result.put("claims", repository.findClaims(id));
        } else if (!"open".equals(view.status()) && view.myClaimId() == null) {
            throw ApiException.notFound("ca trống");
        }
        return result;
    }

    @Transactional
    public ClaimView claim(CurrentUser me, String openShiftId, String note) {
        if (me.admin()) throw ApiException.forbidden("Admin không đăng ký nhận ca.");
        OpenShiftView os = load(openShiftId, me.id());
        requireClaimable(os);
        List<String> blocking = blocking(validator.validate(me.id(), os.shiftId(), null, Set.of(), os.requiredLevelId()));
        if (!blocking.isEmpty()) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "Bạn chưa đủ điều kiện nhận ca này.", blocking);
        }
        String cleanNote = optional(note);
        ClaimView claim;
        var existing = repository.findClaimOf(openShiftId, me.id());
        if (existing.isPresent()) {
            ClaimView c = existing.get();
            if ("pending".equals(c.status()) || "approved".equals(c.status())) {
                return c; // gửi lặp → giữ nguyên
            }
            if (!"withdrawn".equals(c.status()) || repository.reopenWithdrawnClaim(c.id(), cleanNote) != 1) {
                throw ApiException.conflict("CLAIM_CLOSED", "Đăng ký trước đó của bạn đã được xử lý.");
            }
            claim = repository.findClaim(c.id()).orElseThrow();
        } else {
            String id = Ids.next("claim");
            try {
                repository.insertClaim(id, openShiftId, me.id(), cleanNote);
            } catch (DuplicateKeyException e) {
                return repository.findClaimOf(openShiftId, me.id()).orElseThrow();
            }
            claim = repository.findClaim(id).orElseThrow();
        }
        audit.record("open_shift_claim", "Đăng ký nhận ca trống", label(os), me.id(), null,
                Map.of("claimId", claim.id(), "openShiftId", openShiftId), cleanNote);
        events.publish(new DomainEvent(DomainEvent.idOf(EventTypes.OPEN_SHIFT_CLAIMED, claim.id(), claim.version()),
                EventTypes.OPEN_SHIFT_CLAIMED, "open_shift_claim", claim.id(), claim.version(), me.id(),
                List.of(DomainEvent.ROLE_ADMIN), "Có người đăng ký ca trống",
                claim.userName() + " đăng ký nhận " + label(os) + ".", "open-shifts"));
        return claim;
    }

    @Transactional
    public ClaimView withdraw(CurrentUser me, String openShiftId) {
        ClaimView c = repository.findClaimOf(openShiftId, me.id())
                .orElseThrow(() -> ApiException.notFound("đăng ký"));
        if ("withdrawn".equals(c.status())) return c;
        if (!"pending".equals(c.status()) || repository.withdrawClaim(c.id(), c.version()) != 1) {
            throw ApiException.conflict("INVALID_STATE", "Đăng ký đã được xử lý, không thể rút.");
        }
        ClaimView updated = repository.findClaim(c.id()).orElseThrow();
        audit.record("open_shift_withdraw", "Rút đăng ký ca trống", openShiftId, me.id(), null,
                Map.of("claimId", c.id()), null);
        return updated;
    }

    @Transactional
    public ClaimView approveClaim(CurrentUser me, String openShiftId, String claimId, Decision d) {
        me.requireAdmin();
        ClaimView claim = loadClaim(openShiftId, claimId);
        if ("approved".equals(claim.status())) return claim; // duyệt lặp
        if (!"pending".equals(claim.status())) {
            throw ApiException.conflict("INVALID_STATE", "Đăng ký không còn chờ duyệt.");
        }
        if (d.expectedVersion() != null && d.expectedVersion() != claim.version()) {
            throw ApiException.conflict("STALE_VERSION", "Đăng ký đã thay đổi, vui lòng tải lại.");
        }
        OpenShiftView os = load(openShiftId, me.id());
        List<String> blocking = blocking(validator.validate(claim.userId(), os.shiftId(), null, Set.of(),
                os.requiredLevelId()));
        if (!blocking.isEmpty()) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "Không thể duyệt: vi phạm ràng buộc lịch.", blocking);
        }
        if (repository.reserveSlot(openShiftId) != 1) {
            throw ApiException.conflict("OPEN_SHIFT_FULL", "Ca trống đã đủ người hoặc đã đóng.");
        }
        String note = optional(d.note());
        if (repository.decideClaim(claimId, claim.version(), "approved", me.id(), note) != 1) {
            // Ném lỗi → rollback cả phần giữ chỗ ở trên.
            ClaimView latest = repository.findClaim(claimId).orElseThrow();
            if ("approved".equals(latest.status())) {
                throw ApiException.conflict("ALREADY_APPROVED", "Đăng ký vừa được duyệt bởi người khác.");
            }
            throw ApiException.conflict("STALE_VERSION", "Đăng ký vừa thay đổi, vui lòng tải lại.");
        }
        String assignmentId = schedule.addAssignment(os.shiftId(), claim.userId(), me.id(),
                "Nhận ca trống " + openShiftId);
        repository.setClaimAssignment(claimId, assignmentId);
        ClaimView updated = repository.findClaim(claimId).orElseThrow();
        OpenShiftView after = load(openShiftId, me.id());

        audit.record("open_shift_claim_approve", "Duyệt nhận ca trống", label(os) + " — " + claim.userName(), me.id(),
                Map.of("filledCount", os.filledCount()),
                Map.of("filledCount", after.filledCount(), "assignmentId", assignmentId, "userId", claim.userId()), note);
        events.publish(new DomainEvent(
                DomainEvent.idOf(EventTypes.OPEN_SHIFT_CLAIM_APPROVED, claimId, updated.version()),
                EventTypes.OPEN_SHIFT_CLAIM_APPROVED, "open_shift_claim", claimId, updated.version(), me.id(),
                List.of(claim.userId()), "Bạn đã nhận ca thành công",
                "Bạn được phân vào " + label(os) + ".", "my-schedule"));
        if ("filled".equals(after.status())) {
            List<String> others = repository.rejectPendingClaims(openShiftId, me.id(), "Ca trống đã đủ người.");
            notifyRejected(openShiftId, after, others, me.id(), "Ca trống đã đủ người");
        }
        return updated;
    }

    @Transactional
    public ClaimView rejectClaim(CurrentUser me, String openShiftId, String claimId, Decision d) {
        me.requireAdmin();
        ClaimView claim = loadClaim(openShiftId, claimId);
        if ("rejected".equals(claim.status())) return claim;
        if (!"pending".equals(claim.status())) {
            throw ApiException.conflict("INVALID_STATE", "Đăng ký không còn chờ duyệt.");
        }
        if (d.expectedVersion() != null && d.expectedVersion() != claim.version()) {
            throw ApiException.conflict("STALE_VERSION", "Đăng ký đã thay đổi, vui lòng tải lại.");
        }
        String reason = required(d.note(), "Lý do từ chối");
        if (repository.decideClaim(claimId, claim.version(), "rejected", me.id(), reason) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Đăng ký vừa thay đổi, vui lòng tải lại.");
        }
        ClaimView updated = repository.findClaim(claimId).orElseThrow();
        OpenShiftView os = load(openShiftId, me.id());
        audit.record("open_shift_claim_reject", "Từ chối nhận ca trống", label(os) + " — " + claim.userName(),
                me.id(), null, Map.of("claimId", claimId), reason);
        events.publish(new DomainEvent(
                DomainEvent.idOf(EventTypes.OPEN_SHIFT_CLAIM_REJECTED, claimId, updated.version()),
                EventTypes.OPEN_SHIFT_CLAIM_REJECTED, "open_shift_claim", claimId, updated.version(), me.id(),
                List.of(claim.userId()), "Đăng ký ca trống không được chọn", label(os) + ": " + reason, "open-shifts"));
        return updated;
    }

    /** Đóng (closed) hoặc hủy (cancelled) ca trống; đăng ký còn chờ bị từ chối kèm lý do. */
    @Transactional
    public OpenShiftView close(CurrentUser me, String openShiftId, boolean cancel, Decision d) {
        me.requireAdmin();
        OpenShiftView os = load(openShiftId, me.id());
        if (!"open".equals(os.status())) {
            throw ApiException.conflict("INVALID_STATE", "Ca trống không còn mở.");
        }
        int version = d.expectedVersion() == null ? os.version() : d.expectedVersion();
        String status = cancel ? "cancelled" : "closed";
        if (repository.close(openShiftId, version, status) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Ca trống vừa thay đổi, vui lòng tải lại.");
        }
        String reason = optional(d.note());
        String message = cancel ? "Ca trống đã bị hủy" : "Ca trống đã đóng";
        List<String> affected = repository.rejectPendingClaims(openShiftId, me.id(),
                reason == null ? message + "." : message + ": " + reason);
        OpenShiftView after = load(openShiftId, me.id());
        audit.record("open_shift_" + status, message, label(os), me.id(), Map.of("status", os.status()),
                Map.of("status", status), reason);
        notifyRejected(openShiftId, after, affected, me.id(), message);
        return after;
    }

    public long pendingClaimCount(CurrentUser me) {
        me.requireAdmin();
        return repository.countPendingClaims();
    }

    /* ── Hỗ trợ ──────────────────────────────────────────────────────── */

    private void notifyRejected(String openShiftId, OpenShiftView os, List<String> userIds, String actorId,
                                String reason) {
        if (userIds.isEmpty()) return;
        events.publish(new DomainEvent(
                DomainEvent.idOf(EventTypes.OPEN_SHIFT_CLAIM_REJECTED, openShiftId, os.version()),
                EventTypes.OPEN_SHIFT_CLAIM_REJECTED, "open_shift", openShiftId, os.version(), actorId, userIds,
                "Đăng ký ca trống không được chọn", label(os) + ": " + reason + ".", "open-shifts"));
    }

    private void requireClaimable(OpenShiftView os) {
        if (!"open".equals(os.status())) {
            throw ApiException.conflict("OPEN_SHIFT_CLOSED", "Ca trống đã đóng hoặc đã đủ người.");
        }
        LocalDateTime nowUtc = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        if (!os.claimDeadlineUtc().isAfter(nowUtc)) {
            throw ApiException.conflict("DEADLINE_PASSED", "Đã hết hạn nhận ca.");
        }
    }

    private boolean canSubstitute(String userLevel, String neededLevel) {
        if (userLevel == null) return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM level_substitutions WHERE higher_level_id = ? AND lower_level_id = ?",
                Integer.class, userLevel, neededLevel);
        return n != null && n > 0;
    }

    private LocalDateTime toUtc(LocalDateTime local) {
        return local.atZone(clock.getZone()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private OpenShiftView load(String id, String viewerId) {
        return repository.findById(id, viewerId).orElseThrow(() -> ApiException.notFound("ca trống"));
    }

    private ClaimView loadClaim(String openShiftId, String claimId) {
        ClaimView c = repository.findClaim(claimId).orElseThrow(() -> ApiException.notFound("đăng ký"));
        if (!c.openShiftId().equals(openShiftId)) throw ApiException.notFound("đăng ký");
        return c;
    }

    private static List<String> blocking(List<Violation> violations) {
        return violations.stream().filter(Violation::blocking).map(Violation::message).toList();
    }

    private static String label(OpenShiftView os) {
        return os.shiftName() + " " + os.shiftDate() + " " + os.startTime() + "–" + os.endTime();
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        if (v.length() > 1000) throw ApiException.badRequest("TOO_LONG", "Ghi chú tối đa 1000 ký tự.");
        return v;
    }

    private static String required(String value, String field) {
        String v = optional(value);
        if (v == null) throw ApiException.badRequest("REQUIRED", field + " là bắt buộc.");
        return v;
    }
}
