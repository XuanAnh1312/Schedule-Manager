package org.dacnpm.schedulemanager.schedule.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.dacnpm.schedulemanager.schedule.plan.PlanException;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanPair;
import org.dacnpm.schedulemanager.schedule.plan.PlanRepository.PlanRow;
import org.dacnpm.schedulemanager.schedule.plan.PlanService;
import org.dacnpm.schedulemanager.schedule.plan.PlanService.PlanView;
import org.dacnpm.schedulemanager.schedule.plan.RosterChecker;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository;
import org.dacnpm.schedulemanager.schedule.plan.ScheduleDataRepository.ScheduleData;
import org.dacnpm.schedulemanager.schedule.shift.ShiftRepository;
import org.dacnpm.schedulemanager.schedule.validation.ValidationResult;
import org.dacnpm.schedulemanager.schedule.validation.Violation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ngoại lệ phân công (TW-06): quyền riêng {@value #PERMISSION} → xem trước vi phạm → xác nhận kèm lý do → ghi
 * schedule_exceptions và lịch sử. Chỉ các mã vi phạm có {@code overridable = true} (thời gian rảnh, ngày không thể làm)
 * được xin ngoại lệ; còn bất kỳ vi phạm bắt buộc nào (trùng giờ, vượt trần, nghỉ tối thiểu, Level, chứng chỉ…) thì từ
 * chối, không có đường vòng.
 *
 * <p>Bản xem trước có {@code previewToken} băm từ danh sách vi phạm; khi xác nhận, vi phạm được tính lại và phải khớp
 * token, nên dữ liệu đổi giữa lúc xem và lúc xác nhận thì phải xem lại.
 */
@Profile("sqlserver")
@Service
public class ExceptionService {

    public static final String PERMISSION = "schedule.exception.approve";
    static final int MAX_REASON = 1000;

    /**
     * @param exceptionNeeded có vi phạm thuộc nhóm có thể xin ngoại lệ
     * @param allowed         được phép xin ngoại lệ: cần ngoại lệ và không có vi phạm bắt buộc
     */
    public record Preview(String planId, String shiftId, String userId, boolean exceptionNeeded, boolean allowed,
                          List<Violation> blocking, List<Violation> exceptionable, String previewToken, String message) {
    }

    public record ExceptionView(String id, String planId, String shiftId, String userId, Set<String> codes,
                                String reason, String approvedBy, PlanView plan) {
    }

    private final ExceptionRepository repository;
    private final PlanRepository plans;
    private final PlanService planService;
    private final ScheduleDataRepository data;
    private final RosterChecker checker;
    private final ShiftRepository history;
    private final ObjectMapper mapper;

    public ExceptionService(ExceptionRepository repository, PlanRepository plans, PlanService planService,
                            ScheduleDataRepository data, RosterChecker checker, ShiftRepository history,
                            ObjectMapper mapper) {
        this.repository = repository;
        this.plans = plans;
        this.planService = planService;
        this.data = data;
        this.checker = checker;
        this.history = history;
        this.mapper = mapper;
    }

    /** Xem trước vi phạm nếu thêm nhân viên vào ca của phương án nháp. Không ghi gì. */
    @Transactional(readOnly = true)
    public Preview previewForPlan(String planId, String shiftId, String userId, String requiredLevelId, String actorId) {
        requirePermission(actorId);
        PlanRow row = plans.find(planId).orElseThrow(() -> PlanException.notFound("Không tìm thấy phương án " + planId + "."));
        if (!PlanRepository.DRAFT.equals(row.status())) {
            throw PlanException.notEditable("Chỉ xin ngoại lệ cho phương án nháp; phương án đang " + row.status() + ".");
        }
        PlanView view = planService.get(planId);
        if (!view.shiftIds().contains(shiftId)) {
            throw new IllegalArgumentException("Ca " + shiftId + " không thuộc phương án " + planId + ".");
        }
        ScheduleData d = data.load(row.periodId())
                .orElseThrow(() -> PlanException.notFound("Không tìm thấy kỳ " + row.periodId() + "."));
        PlanPair pair = new PlanPair(shiftId, userId);
        return preview(planId, pair, checker.checkPair(d, view.shiftIds(), view.roster(), pair, requiredLevelId));
    }

    /** Xác nhận ngoại lệ và thêm nhân viên vào phương án nháp trong cùng giao dịch. */
    @Transactional
    public ExceptionView approveForPlan(String planId, int expectedVersion, String shiftId, String userId,
                                        String requiredLevelId, String reason, String previewToken, Boolean confirm,
                                        String actorId) {
        String why = requireReasonAndConfirm(reason, confirm);
        Preview preview = previewForPlan(planId, shiftId, userId, requiredLevelId, actorId);
        verify(preview, previewToken);
        if (!plans.touchDraft(planId, expectedVersion)) {
            throw PlanException.conflict("Phương án đã được thay đổi hoặc không còn là nháp. Hãy tải lại.");
        }
        plans.addPair(planId, new PlanPair(shiftId, userId));
        String id = record(planId, preview, why, actorId);
        return new ExceptionView(id, planId, shiftId, userId, codes(preview), why, actorId, planService.get(planId));
    }

    /**
     * Ghi ngoại lệ cho thay đổi trực tiếp trên lịch chính thức. Bên gọi đã tính vi phạm trong giao dịch của mình và
     * truyền vào; phương thức kiểm tra quyền, xác nhận, lý do và token rồi ghi audit.
     *
     * @return id ngoại lệ
     */
    @Transactional
    public String approveOfficial(String shiftId, String userId, ValidationResult result, String reason,
                                  String previewToken, Boolean confirm, String actorId) {
        requirePermission(actorId);
        String why = requireReasonAndConfirm(reason, confirm);
        Preview preview = preview(null, new PlanPair(shiftId, userId), result);
        verify(preview, previewToken);
        return record(null, preview, why, actorId);
    }

    /** Xem trước cho lịch chính thức từ kết quả kiểm tra bên gọi đã tính. */
    public Preview previewOfficial(String shiftId, String userId, ValidationResult result, String actorId) {
        requirePermission(actorId);
        return preview(null, new PlanPair(shiftId, userId), result);
    }

    static Preview preview(String planId, PlanPair pair, ValidationResult result) {
        List<Violation> blocking = result.violations().stream().filter(v -> !v.code().overridable()).toList();
        List<Violation> exceptionable = result.violations().stream().filter(v -> v.code().overridable()).toList();
        boolean needed = !exceptionable.isEmpty();
        boolean allowed = needed && blocking.isEmpty();
        String message = !blocking.isEmpty()
                ? "Có vi phạm ràng buộc bắt buộc; không thể duyệt ngoại lệ."
                : needed ? "Có thể duyệt ngoại lệ cho các vi phạm được liệt kê, bắt buộc nhập lý do."
                : "Không có vi phạm, không cần ngoại lệ.";
        return new Preview(planId, pair.shiftId(), pair.userId(), needed, allowed, blocking, exceptionable,
                token(pair, result.violations()), message);
    }

    static String token(PlanPair pair, List<Violation> violations) {
        String canonical = pair.shiftId() + "|" + pair.userId() + "|" + violations.stream()
                .sorted(Comparator.comparing((Violation v) -> v.code().name()).thenComparing(Violation::message))
                .map(v -> v.code().name() + ":" + v.message())
                .collect(Collectors.joining(";"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void verify(Preview preview, String previewToken) {
        if (!Objects.equals(preview.previewToken(), previewToken)) {
            throw PlanException.of(PlanException.STALE_PREVIEW, 409,
                    "Vi phạm đã thay đổi so với bản xem trước. Hãy xem trước lại rồi xác nhận.", List.of());
        }
        if (!preview.blocking().isEmpty()) {
            throw PlanException.of(PlanException.HARD_VIOLATIONS, 422,
                    "Không thể duyệt ngoại lệ cho ràng buộc bắt buộc.",
                    preview.blocking().stream().map(Violation::message).toList());
        }
        if (!preview.exceptionNeeded()) {
            throw new IllegalArgumentException("Phân công này không vi phạm gì, không cần ngoại lệ.");
        }
    }

    private String record(String planId, Preview preview, String reason, String actorId) {
        String id = "exc_" + UUID.randomUUID().toString().replace("-", "");
        String violationsJson = json(preview.exceptionable());
        repository.insert(id, planId, preview.shiftId(), preview.userId(), codes(preview), violationsJson, reason, actorId);
        history.writeHistory("schedule_exception", "Duyệt ngoại lệ phân công",
                id + ": " + preview.userId() + " vào ca " + preview.shiftId()
                        + (planId == null ? " (lịch chính thức)" : " (phương án " + planId + ")")
                        + ", vi phạm " + String.join(", ", codes(preview)) + ".",
                actorId, null, violationsJson, reason);
        return id;
    }

    private static Set<String> codes(Preview preview) {
        return preview.exceptionable().stream().map(v -> v.code().name()).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    void requirePermission(String actorId) {
        if (actorId == null || !repository.hasPermission(actorId, PERMISSION)) {
            throw PlanException.of(PlanException.FORBIDDEN, 403,
                    "Cần quyền " + PERMISSION + " để xem trước hoặc duyệt ngoại lệ phân công.", List.of());
        }
    }

    private static String requireReasonAndConfirm(String reason, Boolean confirm) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw PlanException.of(PlanException.CONFIRM_REQUIRED, 400, "Cần xác nhận (confirm = true) để duyệt ngoại lệ.",
                    List.of());
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Lý do ngoại lệ không được để trống.");
        }
        if (reason.trim().length() > MAX_REASON) {
            throw new IllegalArgumentException("Lý do tối đa " + MAX_REASON + " ký tự.");
        }
        return reason.trim();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không ghi được JSON.", e);
        }
    }
}
