package org.dacnpm.schedulemanager.schedule.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.dacnpm.schedulemanager.schedule.engine.EngineResult;
import org.dacnpm.schedulemanager.schedule.engine.ScheduleEngine;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanRow;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phương án phân lịch (TW-04).
 *
 * <ul>
 *   <li>Chạy thử: chạy engine và trả kết quả, không ghi bất cứ thứ gì.</li>
 *   <li>Lưu nháp / chạy lại: chỉ ghi schedule_plans và plan_assignments; lịch chính thức (assignments, shifts) không
 *       đổi, nên nháp không hiện thành lịch của nhân viên. Chạy lại thay toàn bộ phân công nháp cũ và trả về thay đổi
 *       so với lần chạy trước.</li>
 *   <li>Theo hợp đồng với module kỳ: chạy thử khi kỳ open hoặc locked; lưu/chạy lại phương án chính thức chỉ khi kỳ
 *       locked.</li>
 * </ul>
 * Duyệt, công bố và áp phương án vào lịch chính thức thuộc TW-05.
 */
@Profile("sqlserver")
@Service
public class PlanService {

    static final int MAX_NAME = 120;
    static final int MAX_REASON = 1000;

    /**
     * @param shiftIds        ca cần phân; rỗng/null là mọi ca chưa công bố của kỳ tại thời điểm chạy
     * @param replaceExisting true: phân công đã lưu của các ca này được thay thế (không tính giờ), engine xếp lại từ
     *                        đầu; false: giữ phân công đã lưu và chỉ điền phần còn thiếu
     */
    public record PlanOptions(List<String> shiftIds, boolean replaceExisting) {
        public PlanOptions {
            shiftIds = shiftIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(shiftIds));
        }
    }

    /** Nội dung cột result_json. */
    public record StoredResult(PlanOptions options, EngineResult result) {
    }

    public record DryRunView(String periodId, String periodStatus, PlanOptions options, List<String> shiftIds,
                             EngineResult result) {
    }

    public record PlanView(String id, String periodId, String name, String status, int version, int runCount,
                           String createdBy, LocalDateTime createdAt, LocalDateTime updatedAt, PlanOptions options,
                           List<String> shiftIds, EngineResult result) {
    }

    public record PlanSummaryView(String id, String periodId, String name, String status, int version, int runCount,
                                  LocalDateTime createdAt, LocalDateTime updatedAt, EngineResult.Summary summary) {
    }

    /** Khác biệt phân công giữa hai phương án (hoặc hai lần chạy của cùng phương án). */
    public record PlanDiff(String baseId, String otherId, int added, int removed, int unchanged,
                           List<PlanPair> addedAssignments, List<PlanPair> removedAssignments,
                           EngineResult.Summary baseSummary, EngineResult.Summary otherSummary) {
    }

    public record RerunView(PlanView plan, PlanDiff changes) {
    }

    private record Execution(String periodStatus, PlanOptions options, List<String> shiftIds, EngineResult result) {
    }

    private final PlanRepository plans;
    private final ScheduleDataRepository data;
    private final ScheduleEngine engine;
    private final ShiftRepository history;
    private final ObjectMapper mapper;

    public PlanService(PlanRepository plans, ScheduleDataRepository data, ScheduleEngine engine,
                       ShiftRepository history, ObjectMapper mapper) {
        this.plans = plans;
        this.data = data;
        this.engine = engine;
        this.history = history;
        this.mapper = mapper;
    }

    /** Chạy thử: không ghi dữ liệu nào. */
    @Transactional(readOnly = true)
    public DryRunView dryRun(String periodId, PlanOptions options) {
        Execution run = execute(required(periodId, "periodId"), options, false);
        return new DryRunView(periodId, run.periodStatus(), run.options(), run.shiftIds(), run.result());
    }

    @Transactional
    public PlanView create(String periodId, String name, PlanOptions options, String actorId) {
        String planName = name(name);
        Execution run = execute(required(periodId, "periodId"), options, true);
        String id = "plan_" + UUID.randomUUID().toString().replace("-", "");
        plans.insert(id, periodId, planName, json(run.shiftIds()), json(new StoredResult(run.options(), run.result())),
                actorId);
        plans.replaceAssignments(id, pairs(run.result()));
        history.writeHistory("plan_create", "Tạo phương án " + planName, describe(id, run.result()), actorId,
                null, null, null);
        return get(id);
    }

    /** Chạy lại với cùng tùy chọn; phân công nháp cũ được thay toàn bộ, không cộng dồn. */
    @Transactional
    public RerunView rerun(String planId, int expectedVersion, String actorId) {
        PlanRow row = row(planId);
        requireDraft(row);
        if (row.version() != expectedVersion) {
            throw PlanException.conflict("Phương án đã được thay đổi (version " + row.version() + "). Hãy tải lại.");
        }
        StoredResult before = stored(row);
        List<PlanPair> previous = plans.assignments(planId);

        Execution run = execute(row.periodId(), before.options(), true);
        if (!plans.updateRun(planId, expectedVersion, json(run.shiftIds()),
                json(new StoredResult(run.options(), run.result())))) {
            throw PlanException.conflict("Phương án đã được thay đổi hoặc không còn là nháp. Hãy tải lại.");
        }
        List<PlanPair> current = pairs(run.result());
        plans.replaceAssignments(planId, current);
        PlanDiff diff = diff(planId, planId, previous, current, summaryOf(before), run.result().summary());
        history.writeHistory("plan_rerun", "Chạy lại phương án " + row.name(),
                describe(planId, run.result()) + " Thay đổi: +" + diff.added() + " / -" + diff.removed() + ".",
                actorId, null, null, null);
        return new RerunView(get(planId), diff);
    }

    @Transactional
    public PlanView cancel(String planId, int expectedVersion, String reason, String actorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Lý do hủy không được để trống.");
        }
        if (reason.trim().length() > MAX_REASON) {
            throw new IllegalArgumentException("Lý do tối đa " + MAX_REASON + " ký tự.");
        }
        PlanRow row = row(planId);
        requireDraft(row);
        if (!plans.updateStatus(planId, expectedVersion, PlanRepository.DRAFT, PlanRepository.CANCELLED)) {
            throw PlanException.conflict("Phương án đã được thay đổi hoặc không còn là nháp. Hãy tải lại.");
        }
        history.writeHistory("plan_cancel", "Hủy phương án " + row.name(), planId, actorId, null, null, reason.trim());
        return get(planId);
    }

    @Transactional(readOnly = true)
    public PlanView get(String planId) {
        PlanRow row = row(planId);
        StoredResult stored = stored(row);
        return new PlanView(row.id(), row.periodId(), row.name(), row.status(), row.version(), row.runCount(),
                row.createdBy(), row.createdAt(), row.updatedAt(), stored.options(), shiftIds(row), stored.result());
    }

    @Transactional(readOnly = true)
    public List<PlanSummaryView> list(String periodId) {
        return plans.listByPeriod(required(periodId, "periodId")).stream()
                .map(row -> new PlanSummaryView(row.id(), row.periodId(), row.name(), row.status(), row.version(),
                        row.runCount(), row.createdAt(), row.updatedAt(), summaryOf(stored(row))))
                .toList();
    }

    /** So sánh hai phương án: phân công thêm/bớt và thống kê đủ/thiếu của từng bên. */
    @Transactional(readOnly = true)
    public PlanDiff compare(String baseId, String otherId) {
        PlanRow base = row(required(baseId, "baseId"));
        PlanRow other = row(required(otherId, "otherId"));
        return diff(base.id(), other.id(), plans.assignments(base.id()), plans.assignments(other.id()),
                summaryOf(stored(base)), summaryOf(stored(other)));
    }

    /** Phương án chưa hoàn tất của kỳ; dùng để cài SchedulePlanGate của module kỳ khi gộp nhánh. */
    @Transactional(readOnly = true)
    public List<String> unfinishedPlans(String periodId) {
        return plans.unfinishedPlans(periodId);
    }

    private Execution execute(String periodId, PlanOptions options, boolean official) {
        PlanOptions opts = options == null ? new PlanOptions(null, false) : options;
        String status = plans.periodStatus(periodId)
                .orElseThrow(() -> PlanException.notFound("Không tìm thấy kỳ " + periodId + "."));
        if (official && !"locked".equals(status)) {
            throw PlanException.periodNotReady("Chỉ lưu hoặc chạy lại phương án khi kỳ đã khóa đăng ký (locked); kỳ đang "
                    + status + ". Có thể chạy thử khi kỳ open hoặc locked.");
        }
        if (!official && !"open".equals(status) && !"locked".equals(status)) {
            throw PlanException.periodNotReady("Chỉ chạy thử khi kỳ đang mở (open) hoặc đã khóa (locked); kỳ đang "
                    + status + ".");
        }
        ScheduleData d = data.load(periodId)
                .orElseThrow(() -> PlanException.notFound("Không tìm thấy kỳ " + periodId + "."));

        List<String> plannable = d.plannableShiftIds();
        List<String> shiftIds;
        if (opts.shiftIds().isEmpty()) {
            shiftIds = plannable;
        } else {
            Set<String> allowed = Set.copyOf(plannable);
            List<String> invalid = opts.shiftIds().stream().filter(id -> !allowed.contains(id)).toList();
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException("Ca không thuộc kỳ, đã hủy hoặc đã công bố: "
                        + String.join(", ", invalid) + ".");
            }
            shiftIds = opts.shiftIds();
        }
        if (shiftIds.isEmpty()) {
            throw new IllegalArgumentException("Kỳ không có ca nào chưa công bố để phân lịch.");
        }

        Set<String> planned = Set.copyOf(shiftIds);
        Set<String> replaced = opts.replaceExisting()
                ? d.savedAssignments().stream().filter(a -> planned.contains(a.shiftId()))
                        .map(Assignment::id).collect(Collectors.toSet())
                : Set.of();
        EngineResult result = engine.run(d.context(replaced), shiftIds);
        return new Execution(status, opts, shiftIds, result);
    }

    static PlanDiff diff(String baseId, String otherId, List<PlanPair> base, List<PlanPair> other,
                         EngineResult.Summary baseSummary, EngineResult.Summary otherSummary) {
        Set<PlanPair> before = new LinkedHashSet<>(base);
        Set<PlanPair> after = new LinkedHashSet<>(other);
        List<PlanPair> added = new ArrayList<>(after);
        added.removeAll(before);
        List<PlanPair> removed = new ArrayList<>(before);
        removed.removeAll(after);
        int unchanged = after.size() - added.size();
        return new PlanDiff(baseId, otherId, added.size(), removed.size(), unchanged, added, removed,
                baseSummary, otherSummary);
    }

    private static List<PlanPair> pairs(EngineResult result) {
        return result.assignments().stream().map(a -> new PlanPair(a.shiftId(), a.userId())).distinct().toList();
    }

    private static String describe(String planId, EngineResult result) {
        EngineResult.Summary s = result.summary();
        return planId + ": " + s.totalShifts() + " ca, " + s.plannedAssignments() + " phân công mới, " + s.full()
                + " đủ người, " + s.belowTarget() + " dưới tổng nhu cầu, " + s.understaffed() + " thiếu người.";
    }

    private PlanRow row(String planId) {
        return plans.find(planId).orElseThrow(() -> PlanException.notFound("Không tìm thấy phương án " + planId + "."));
    }

    private static void requireDraft(PlanRow row) {
        if (!PlanRepository.DRAFT.equals(row.status())) {
            throw PlanException.notEditable("Phương án đang ở trạng thái " + row.status() + "; chỉ phương án nháp mới được "
                    + "chạy lại hoặc hủy ở bước này.");
        }
    }

    private StoredResult stored(PlanRow row) {
        if (row.resultJson() == null) {
            return new StoredResult(new PlanOptions(null, false), null);
        }
        try {
            return mapper.readValue(row.resultJson(), StoredResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không đọc được kết quả của phương án " + row.id() + ".", e);
        }
    }

    private List<String> shiftIds(PlanRow row) {
        try {
            return mapper.readValue(row.shiftIdsJson(), new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không đọc được danh sách ca của phương án " + row.id() + ".", e);
        }
    }

    private static EngineResult.Summary summaryOf(StoredResult stored) {
        return stored.result() == null ? null : stored.result().summary();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không ghi được JSON.", e);
        }
    }

    private static String name(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên phương án không được để trống.");
        }
        if (name.trim().length() > MAX_NAME) {
            throw new IllegalArgumentException("Tên phương án tối đa " + MAX_NAME + " ký tự.");
        }
        return name.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Thiếu thông số " + field + ".");
        }
        return value;
    }
}
