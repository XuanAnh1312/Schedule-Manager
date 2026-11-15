package org.dacnpm.schedulemanager.api.performance;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.dacnpm.schedulemanager.domain.events.EventTypes;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.dacnpm.schedulemanager.api.performance.PerformanceModels.*;

/**
 * Hiệu suất và KPI (BAO-06, PE-01).
 *
 * <pre>
 * Admin định nghĩa KPI ─► tạo kỳ (draft) ─► mở kỳ: tạo phiếu cho nhân viên đang hoạt động, chụp KPI, tự tính số liệu
 *   phiếu: pending_self ──(nhân viên tự đánh giá)──► pending_manager ──(quản lý chấm)──► pending_ack ──(nhân viên phản hồi)──► completed
 * </pre>
 *
 * <ul>
 *   <li>Quyền: nhân viên chỉ xem phiếu của mình; quản lý xem/chấm phiếu mà mình là người đánh giá (quản lý trực tiếp
 *       lúc mở kỳ); Admin xem tất cả và chấm thay. Người khác → 404 (không lộ phiếu).</li>
 *   <li>Mọi chuyển trạng thái là UPDATE có điều kiện (version + status) trong một giao dịch, có audit + thông báo.</li>
 *   <li>Khen thưởng tài chính không tính ở đây: ghi đề xuất và phát sự kiện cho Payroll (Bình); đề xuất đào tạo phát sự
 *       kiện cho LMS (Tuấn). eventId theo id thành tích → bên nhận chỉ nhận một lần.</li>
 * </ul>
 */
@Profile("sqlserver")
@Service
public class PerformanceService {

    static final Set<String> FORMULAS = Set.of(MANUAL, HOURS_VS_SCHEDULE, ATTENDANCE_RATE, ABSENCE_RATE);
    static final Set<String> DIRECTIONS = Set.of(HIGHER_BETTER, LOWER_BETTER);
    static final Set<String> KINDS = Set.of("commendation", "bonus_proposal", "promotion_proposal", "training_proposal");
    static final int MAX_TEXT = 2000;

    private final PerformanceRepository repository;
    private final DomainEventPublisher events;
    private final AuditTrail audit;

    public PerformanceService(PerformanceRepository repository, DomainEventPublisher events, AuditTrail audit) {
        this.repository = repository;
        this.events = events;
        this.audit = audit;
    }

    /* ── KPI ──────────────────────────────────────────────────────────── */

    public List<Kpi> kpis(CurrentUser me, boolean includeInactive) {
        me.requireAdmin();
        return repository.kpis(!includeInactive);
    }

    @Transactional
    public Kpi createKpi(CurrentUser me, KpiInput in) {
        me.requireAdmin();
        String dept = validateKpi(in);
        String id = Ids.next("kpi");
        repository.insertKpi(id, in, dept, me.id());
        Kpi k = repository.kpi(id).orElseThrow();
        audit.record("kpi_create", "Tạo KPI", k.name(), me.id(), null, k, null);
        return k;
    }

    @Transactional
    public Kpi updateKpi(CurrentUser me, String id, KpiInput in) {
        me.requireAdmin();
        Kpi before = repository.kpi(id).orElseThrow(() -> ApiException.notFound("KPI"));
        String dept = validateKpi(in);
        int expected = in.expectedVersion() == null ? before.version() : in.expectedVersion();
        if (repository.updateKpi(id, expected, in, dept, in.active() == null || in.active()) != 1) {
            throw ApiException.conflict("STALE_VERSION", "KPI vừa thay đổi, vui lòng tải lại.");
        }
        Kpi after = repository.kpi(id).orElseThrow();
        // Phiếu đã mở giữ bản chụp KPI cũ; thay đổi chỉ áp dụng cho kỳ mở sau.
        audit.record("kpi_update", "Sửa KPI", after.name(), me.id(), before, after, null);
        return after;
    }

    private String validateKpi(KpiInput in) {
        text(in.name(), "Tên KPI", 150);
        text(in.unit(), "Đơn vị", 30);
        if (in.description() != null && in.description().length() > 1000) {
            throw ApiException.badRequest("TOO_LONG", "Mô tả tối đa 1000 ký tự.");
        }
        if (!FORMULAS.contains(in.formulaType())) {
            throw ApiException.badRequest("INVALID_FORMULA", "Cách tính phải là manual, hours_vs_schedule, attendance_rate hoặc absence_rate.");
        }
        if (!DIRECTIONS.contains(in.direction())) {
            throw ApiException.badRequest("INVALID_DIRECTION", "Hướng phải là higher_better hoặc lower_better.");
        }
        if (ABSENCE_RATE.equals(in.formulaType()) && !LOWER_BETTER.equals(in.direction())) {
            throw ApiException.badRequest("INVALID_DIRECTION", "Tỷ lệ vắng phải là 'thấp là tốt'.");
        }
        if (in.targetValue() == null || in.targetValue().signum() < 0) {
            throw ApiException.badRequest("INVALID_TARGET", "Mục tiêu phải ≥ 0.");
        }
        if (in.weight() == null || in.weight().signum() <= 0 || in.weight().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("INVALID_WEIGHT", "Trọng số từ lớn hơn 0 đến 100.");
        }
        String dept = in.departmentId() == null || in.departmentId().isBlank() ? null : in.departmentId().trim();
        if (dept != null && !repository.departmentExists(dept)) {
            throw ApiException.badRequest("INVALID_DEPARTMENT", "Bộ phận không tồn tại.");
        }
        return dept;
    }

    /* ── Kỳ đánh giá ──────────────────────────────────────────────────── */

    public List<Cycle> cycles(CurrentUser me) {
        me.requireAdmin();
        return repository.cycles();
    }

    @Transactional
    public Cycle createCycle(CurrentUser me, CycleInput in) {
        me.requireAdmin();
        String name = text(in.name(), "Tên kỳ", 150);
        if (in.periodStart() == null || in.periodEnd() == null || in.periodEnd().isBefore(in.periodStart())) {
            throw ApiException.badRequest("INVALID_RANGE", "Khoảng ngày của kỳ không hợp lệ.");
        }
        if (ChronoUnit.DAYS.between(in.periodStart(), in.periodEnd()) > 366) {
            throw ApiException.badRequest("INVALID_RANGE", "Một kỳ đánh giá tối đa 1 năm.");
        }
        int scale = in.scoreScaleMax() == null ? 5 : in.scoreScaleMax();
        if (scale < 3 || scale > 10) throw ApiException.badRequest("INVALID_SCALE", "Thang điểm từ 3 đến 10.");
        String id = Ids.next("rcy");
        repository.insertCycle(id, name, in.periodStart(), in.periodEnd(), scale, me.id());
        Cycle c = repository.cycle(id).orElseThrow();
        audit.record("review_cycle_create", "Tạo kỳ đánh giá", name, me.id(), null, c, null);
        return c;
    }

    /**
     * Mở kỳ: tạo phiếu cho mọi nhân viên đang hoạt động, người đánh giá = quản lý trực tiếp (không có → Admin), chụp
     * KPI áp dụng (chung hoặc đúng bộ phận) và tự tính số liệu. Không có KPI áp dụng → 422.
     */
    @Transactional
    public Cycle openCycle(CurrentUser me, String id, Integer expectedVersion) {
        me.requireAdmin();
        Cycle c = repository.cycle(id).orElseThrow(() -> ApiException.notFound("kỳ đánh giá"));
        int expected = expectedVersion == null ? c.version() : expectedVersion;
        List<Kpi> kpis = repository.kpis(true);
        if (kpis.isEmpty()) {
            throw ApiException.unprocessable("NO_KPI", "Chưa có KPI đang dùng để mở kỳ đánh giá.", List.of());
        }
        if (repository.setCycleStatus(id, expected, "draft", "open") != 1) {
            throw ApiException.conflict("INVALID_STATE", "Chỉ mở được kỳ ở trạng thái nháp (hoặc kỳ vừa thay đổi).");
        }
        List<String> employees = new ArrayList<>();
        for (Map<String, Object> e : repository.activeEmployees()) {
            String userId = (String) e.get("id");
            String dept = (String) e.get("departmentId");
            List<Kpi> applicable = kpis.stream().filter(k -> k.departmentId() == null || k.departmentId().equals(dept)).toList();
            if (applicable.isEmpty()) continue;
            String reviewId = Ids.next("rev");
            repository.insertReview(reviewId, id, userId, (String) e.get("managerId"));
            Metrics m = repository.metrics(userId, c.periodStart(), c.periodEnd());
            for (Kpi k : applicable) {
                repository.insertItem(reviewId, k, KpiCalculator.compute(k.formulaType(), m));
            }
            employees.add(userId);
        }
        Cycle after = repository.cycle(id).orElseThrow();
        audit.record("review_cycle_open", "Mở kỳ đánh giá", c.name(), me.id(), Map.of("status", "draft"),
                Map.of("status", "open", "reviews", employees.size()), null);
        if (!employees.isEmpty()) {
            events.publish(new DomainEvent(DomainEvent.idOf(EventTypes.REVIEW_OPENED, id, after.version()),
                    EventTypes.REVIEW_OPENED, "review_cycle", id, after.version(), me.id(), employees,
                    "Kỳ đánh giá mới: " + c.name(), "Hãy tự đánh giá KPI của bạn cho giai đoạn " + c.periodStart()
                    + " – " + c.periodEnd() + ".", "performance"));
        }
        return after;
    }

    /** Đóng kỳ khi mọi phiếu đã hoàn tất; {@code cancelUnfinished} = hủy các phiếu còn dở (kỳ chuyển cancelled nếu chưa phiếu nào xong). */
    @Transactional
    public Cycle closeCycle(CurrentUser me, String id, Integer expectedVersion, boolean cancelUnfinished) {
        me.requireAdmin();
        Cycle c = repository.cycle(id).orElseThrow(() -> ApiException.notFound("kỳ đánh giá"));
        int expected = expectedVersion == null ? c.version() : expectedVersion;
        long unfinished = repository.countUnfinished(id);
        if (unfinished > 0 && !cancelUnfinished) {
            throw ApiException.conflict("REVIEWS_UNFINISHED", "Còn " + unfinished + " phiếu chưa hoàn tất.");
        }
        if (unfinished > 0) repository.cancelOpenReviews(id);
        String target = c.completed() == 0 && unfinished > 0 ? "cancelled" : "closed";
        String from = "draft".equals(c.status()) ? "draft" : "open";
        if (repository.setCycleStatus(id, expected, from, "draft".equals(from) ? "cancelled" : target) != 1) {
            throw ApiException.conflict("INVALID_STATE", "Kỳ đã đóng hoặc vừa thay đổi.");
        }
        Cycle after = repository.cycle(id).orElseThrow();
        audit.record("review_cycle_close", "Đóng kỳ đánh giá", c.name(), me.id(), Map.of("status", c.status()),
                Map.of("status", after.status(), "cancelledReviews", unfinished), null);
        return after;
    }

    /* ── Phiếu đánh giá ───────────────────────────────────────────────── */

    /** scope = mine (mặc định) | team (tôi là người đánh giá) | all (Admin). */
    public List<Review> reviews(CurrentUser me, String scope, String cycleId) {
        String cycle = cycleId == null || cycleId.isBlank() ? null : cycleId;
        if ("all".equalsIgnoreCase(scope)) {
            me.requireAdmin();
            return repository.reviews(null, null, cycle, false);
        }
        if ("team".equalsIgnoreCase(scope)) {
            return repository.reviews(null, me.id(), cycle, false);
        }
        return repository.reviews(me.id(), null, cycle, false);
    }

    public Review review(CurrentUser me, String id) {
        Review r = repository.review(id).orElseThrow(() -> ApiException.notFound("phiếu đánh giá"));
        if (!canView(me, r)) throw ApiException.notFound("phiếu đánh giá");
        return r;
    }

    @Transactional
    public Review submitSelf(CurrentUser me, String id, SelfReviewInput in) {
        Review r = review(me, id);
        if (!r.employeeId().equals(me.id())) throw ApiException.forbidden("Chỉ nhân viên được đánh giá mới tự đánh giá.");
        if (!"pending_self".equals(r.status())) throw ApiException.conflict("INVALID_STATE", "Phiếu không còn chờ tự đánh giá.");
        Map<String, Item> items = byId(r);
        for (ScoreInput s : in.items() == null ? List.<ScoreInput>of() : in.items()) {
            Item item = requireItem(items, s.itemId());
            requireScore(s.score(), r.scoreScaleMax(), "Điểm tự đánh giá");
            repository.scoreItem(item.id(), id, s.score(), null, null, false, optional(s.comment(), 1000));
        }
        List<Item> updated = repository.items(id);
        if (updated.stream().anyMatch(i -> i.selfScore() == null)) {
            throw ApiException.unprocessable("SCORES_REQUIRED", "Cần tự chấm tất cả KPI trước khi gửi.", missing(updated, true));
        }
        int expected = in.expectedVersion() == null ? r.version() : in.expectedVersion();
        if (repository.submitSelf(id, expected, optional(in.comment(), MAX_TEXT)) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Phiếu vừa thay đổi, vui lòng tải lại.");
        }
        Review after = repository.review(id).orElseThrow();
        audit.record("review_self_submit", "Gửi tự đánh giá", r.employeeName() + " — " + r.cycleName(), me.id(),
                Map.of("status", r.status()), Map.of("status", after.status()), null);
        events.publish(event(after, EventTypes.REVIEW_SELF_SUBMITTED, me.id(),
                List.of(r.reviewerId() == null ? DomainEvent.ROLE_ADMIN : r.reviewerId()),
                "Phiếu chờ bạn đánh giá", r.employeeName() + " đã tự đánh giá kỳ " + r.cycleName() + "."));
        return after;
    }

    /** Quản lý (người đánh giá) hoặc Admin chấm điểm; điểm tổng = trung bình có trọng số. KPI manual cần nhập kết quả. */
    @Transactional
    public Review submitManager(CurrentUser me, String id, ManagerReviewInput in) {
        Review r = review(me, id);
        boolean reviewer = me.id().equals(r.reviewerId());
        if (!reviewer && !me.admin()) throw ApiException.forbidden("Bạn không phải người đánh giá của phiếu này.");
        if (r.employeeId().equals(me.id())) throw ApiException.forbidden("Không được tự chấm điểm quản lý cho chính mình.");
        if (!"pending_manager".equals(r.status())) {
            throw ApiException.conflict("INVALID_STATE", "Phiếu chưa tự đánh giá xong hoặc đã được chấm.");
        }
        Map<String, Item> items = byId(r);
        for (ScoreInput s : in.items() == null ? List.<ScoreInput>of() : in.items()) {
            Item item = requireItem(items, s.itemId());
            requireScore(s.score(), r.scoreScaleMax(), "Điểm quản lý");
            boolean manual = MANUAL.equals(item.formulaType());
            if (!manual && s.actualValue() != null) {
                throw ApiException.badRequest("AUTO_KPI", "KPI " + item.kpiName() + " được tự tính, không nhập tay kết quả.");
            }
            repository.scoreItem(item.id(), id, null, s.score(), s.actualValue(), manual && s.actualValue() != null,
                    optional(s.comment(), 1000));
        }
        List<Item> updated = repository.items(id);
        if (updated.stream().anyMatch(i -> i.managerScore() == null)) {
            throw ApiException.unprocessable("SCORES_REQUIRED", "Cần chấm tất cả KPI trước khi gửi.", missing(updated, false));
        }
        List<String> manualMissing = updated.stream().filter(i -> MANUAL.equals(i.formulaType()) && i.actualValue() == null)
                .map(Item::kpiName).toList();
        if (!manualMissing.isEmpty()) {
            throw ApiException.unprocessable("ACTUAL_REQUIRED", "Cần nhập kết quả cho KPI nhập tay.", manualMissing);
        }
        BigDecimal overall = KpiCalculator.weighted(updated);
        int expected = in.expectedVersion() == null ? r.version() : in.expectedVersion();
        if (repository.submitManager(id, expected, optional(in.comment(), MAX_TEXT), optional(in.improvementPlan(), MAX_TEXT),
                overall, me.id()) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Phiếu vừa thay đổi, vui lòng tải lại.");
        }
        Review after = repository.review(id).orElseThrow();
        audit.record("review_manager_submit", "Chấm phiếu đánh giá" + (reviewer ? "" : " (Admin chấm thay)"),
                r.employeeName() + " — " + r.cycleName(), me.id(), Map.of("status", r.status()),
                Map.of("status", after.status(), "overallScore", overall), null);
        events.publish(event(after, EventTypes.REVIEW_MANAGER_SUBMITTED, me.id(), List.of(r.employeeId()),
                "Đã có kết quả đánh giá", "Kỳ " + r.cycleName() + ": " + overall + "/" + r.scoreScaleMax()
                        + ". Hãy xem và phản hồi."));
        return after;
    }

    @Transactional
    public Review acknowledge(CurrentUser me, String id, AcknowledgeInput in) {
        Review r = review(me, id);
        if (!r.employeeId().equals(me.id())) throw ApiException.forbidden("Chỉ nhân viên được đánh giá mới phản hồi.");
        if (!"pending_ack".equals(r.status())) throw ApiException.conflict("INVALID_STATE", "Phiếu không còn chờ phản hồi.");
        int expected = in.expectedVersion() == null ? r.version() : in.expectedVersion();
        if (repository.acknowledge(id, expected, optional(in.feedback(), MAX_TEXT)) != 1) {
            throw ApiException.conflict("STALE_VERSION", "Phiếu vừa thay đổi, vui lòng tải lại.");
        }
        Review after = repository.review(id).orElseThrow();
        audit.record("review_acknowledge", "Phản hồi kết quả đánh giá", r.employeeName() + " — " + r.cycleName(),
                me.id(), Map.of("status", r.status()), Map.of("status", after.status()), in.feedback());
        return after;
    }

    /* ── Thành tích & đề xuất ─────────────────────────────────────────── */

    /** Ghi thành tích/đề xuất (quản lý trực tiếp hoặc Admin). Đề xuất thưởng/đào tạo phát sự kiện cho Payroll/LMS. */
    @Transactional
    public Achievement recordAchievement(CurrentUser me, AchievementInput in) {
        if (in.employeeId() == null || in.employeeId().isBlank()) throw ApiException.badRequest("REQUIRED", "Nhân viên là bắt buộc.");
        if (!me.admin() && !repository.isManagerOf(me.id(), in.employeeId())) {
            throw ApiException.forbidden("Chỉ quản lý trực tiếp hoặc Admin ghi thành tích cho nhân viên này.");
        }
        if (in.employeeId().equals(me.id())) throw ApiException.forbidden("Không tự ghi thành tích cho chính mình.");
        if (!KINDS.contains(in.kind())) throw ApiException.badRequest("INVALID_KIND", "Loại thành tích không hợp lệ.");
        text(in.title(), "Tiêu đề", 200);
        if (in.description() != null && in.description().length() > 1000) {
            throw ApiException.badRequest("TOO_LONG", "Mô tả tối đa 1000 ký tự.");
        }
        boolean bonus = "bonus_proposal".equals(in.kind());
        if (bonus && (in.suggestedAmount() == null || in.suggestedAmount().signum() <= 0)) {
            throw ApiException.badRequest("AMOUNT_REQUIRED", "Đề xuất thưởng cần số tiền đề xuất (VND) > 0.");
        }
        if (!bonus && in.suggestedAmount() != null) {
            throw ApiException.badRequest("AMOUNT_NOT_ALLOWED", "Chỉ đề xuất thưởng mới có số tiền.");
        }
        if (in.reviewId() != null && !in.reviewId().isBlank()) {
            Review r = repository.review(in.reviewId()).orElseThrow(() -> ApiException.badRequest("INVALID_REVIEW", "Phiếu đánh giá không tồn tại."));
            if (!r.employeeId().equals(in.employeeId())) {
                throw ApiException.badRequest("INVALID_REVIEW", "Phiếu đánh giá không thuộc nhân viên này.");
            }
        }
        AchievementInput clean = new AchievementInput(in.employeeId(), in.kind(), in.title(), in.description(),
                in.suggestedAmount(), in.reviewId() == null || in.reviewId().isBlank() ? null : in.reviewId());
        String id = Ids.next("ach");
        repository.insertAchievement(id, clean, me.id());
        Achievement a = repository.achievements(in.employeeId()).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        audit.record("achievement_record", "Ghi thành tích/đề xuất", a.employeeName() + " — " + a.title(), me.id(), null,
                a, null);
        String type = switch (in.kind()) {
            case "bonus_proposal" -> EventTypes.PERFORMANCE_BONUS_PROPOSED;
            case "training_proposal" -> EventTypes.PERFORMANCE_TRAINING_PROPOSED;
            case "promotion_proposal" -> EventTypes.PERFORMANCE_PROMOTION_PROPOSED;
            default -> EventTypes.PERFORMANCE_COMMENDATION;
        };
        List<String> recipients = "commendation".equals(in.kind())
                ? List.of(in.employeeId()) : List.of(DomainEvent.ROLE_ADMIN, in.employeeId());
        events.publish(new DomainEvent(DomainEvent.idOf(type, id, 0), type, "achievement", id, 0, me.id(), recipients,
                a.title(), a.employeeName() + (bonus ? " — đề xuất thưởng " + a.suggestedAmount().toPlainString()
                + " VND (Payroll xét duyệt)" : "") + (a.description() == null ? "" : ": " + a.description()),
                "performance"));
        return a;
    }

    /** Nhân viên xem thành tích của mình; quản lý xem của cấp dưới; Admin xem tất cả (employeeId null = tất cả). */
    public List<Achievement> achievements(CurrentUser me, String employeeId) {
        String target = employeeId == null || employeeId.isBlank() ? null : employeeId;
        if (me.admin()) return repository.achievements(target);
        if (target == null || target.equals(me.id())) return repository.achievements(me.id());
        if (repository.isManagerOf(me.id(), target)) return repository.achievements(target);
        throw ApiException.forbidden("Bạn không xem được thành tích của nhân viên này.");
    }

    /* ── Hỗ trợ ───────────────────────────────────────────────────────── */

    boolean canView(CurrentUser me, Review r) {
        return me.admin() || me.id().equals(r.employeeId()) || me.id().equals(r.reviewerId());
    }

    private DomainEvent event(Review r, String type, String actor, List<String> recipients, String title, String body) {
        return new DomainEvent(DomainEvent.idOf(type, r.id(), r.version()), type, "review", r.id(), r.version(), actor,
                recipients, title, body, "performance");
    }

    private static Map<String, Item> byId(Review r) {
        return r.items().stream().collect(Collectors.toMap(Item::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static Item requireItem(Map<String, Item> items, String id) {
        Item item = items.get(id);
        if (item == null) throw ApiException.badRequest("INVALID_ITEM", "KPI không thuộc phiếu này.");
        return item;
    }

    private static void requireScore(BigDecimal score, int max, String field) {
        if (score == null || score.compareTo(BigDecimal.ONE) < 0 || score.compareTo(BigDecimal.valueOf(max)) > 0
                || score.scale() > 1 && score.stripTrailingZeros().scale() > 1) {
            throw ApiException.badRequest("INVALID_SCORE", field + " phải từ 1 đến " + max + " (tối đa 1 chữ số thập phân).");
        }
    }

    private static List<String> missing(List<Item> items, boolean self) {
        return items.stream().filter(i -> (self ? i.selfScore() : i.managerScore()) == null).map(Item::kpiName).toList();
    }

    static String text(String v, String field, int max) {
        if (v == null || v.isBlank()) throw ApiException.badRequest("REQUIRED", field + " là bắt buộc.");
        if (v.trim().length() > max) throw ApiException.badRequest("TOO_LONG", field + " tối đa " + max + " ký tự.");
        return v.trim();
    }

    static String optional(String v, int max) {
        if (v == null || v.isBlank()) return null;
        if (v.trim().length() > max) throw ApiException.badRequest("TOO_LONG", "Tối đa " + max + " ký tự.");
        return v.trim();
    }
}
