package org.dacnpm.schedulemanager.api.rules;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Request/response của API quy tắc (TU-05 … TU-07). */
public final class RuleModels {

    private RuleModels() {}

    /* ── Danh mục ────────────────────────────────────────────────────────── */

    public record ParameterInfo(String key, String label, String unit, String description, String valueType,
                                BigDecimal min, BigDecimal max, List<String> allowedValues, String kind,
                                String category, int categoryPriority, boolean requiredForScheduling,
                                String requiredWhen, boolean companyOnly, boolean immediateOnly) {}

    public record ScopeInfo(String scopeType, int precedence, boolean available, String note) {}

    /** Cấu hình liên quan nằm ở module khác (để giao diện dẫn tới đúng chỗ, không lưu hai nơi). */
    public record RelatedSetting(String name, String managedAt, String note) {}

    public record Catalog(List<ParameterInfo> parameters, List<ScopeInfo> scopes, List<String> conflictOrder,
                          List<RelatedSetting> relatedSettings) {}

    /* ── Giá trị đã lưu ──────────────────────────────────────────────────── */

    public record Setting(String id, String paramKey, String scopeType, String scopeId, String value,
                          LocalDate effectiveFrom, String status, String changeId, String createdBy,
                          LocalDateTime createdAt) {}

    /* ── Thay đổi: xem trước → xác nhận ──────────────────────────────────── */

    /**
     * Một thay đổi. {@code op}: set (mặc định) | remove (bỏ giá trị của phạm vi, có hiệu lực ngay).
     * {@code effectiveFrom} mặc định hôm nay, không được ở quá khứ.
     */
    public record ChangeItem(String op, String paramKey, String scopeType, String scopeId, String value,
                             LocalDate effectiveFrom) {}

    public record ChangeRequest(List<ChangeItem> items, String reason) {}

    public record ApplyRequest(Boolean confirm) {}

    public record ValueChange(String key, String before, String after) {}

    public record UserChange(String userId, String employeeCode, String fullName, List<ValueChange> changes,
                             boolean readyBefore, boolean readyAfter) {}

    public record PeriodImpact(String periodId, String name, String status, LocalDate startDate, LocalDate endDate,
                               String note) {}

    /**
     * Kết quả xem trước. Chưa có gì thay đổi cho tới khi gọi apply với {@code confirm=true}.
     *
     * @param companyChanges   thay đổi tham số toàn công ty (trọng số, làm tròn, Level thay thế)
     * @param affectedUsers    số nhân viên có giá trị hiệu lực thay đổi
     * @param userChanges      chi tiết (tối đa 100 người đầu tiên)
     * @param notReadyAfter    số nhân viên vẫn thiếu cấu hình bắt buộc sau thay đổi
     */
    public record Preview(String changeId, String status, int baseRevision, String reason, LocalDate evaluatedAt,
                          List<ChangeItem> items, List<ValueChange> companyChanges, int affectedUsers,
                          List<UserChange> userChanges, int notReadyAfter, List<PeriodImpact> periods,
                          List<String> warnings, String note) {}

    public record ChangeView(String id, String status, String reason, int baseRevision, List<ChangeItem> items,
                             JsonNode preview, String createdBy, LocalDateTime createdAt, String appliedBy,
                             LocalDateTime appliedAt) {}

    /* ── Giá trị có hiệu lực ─────────────────────────────────────────────── */

    public record Source(String scopeType, String scopeId, LocalDate effectiveFrom, String settingId, String changeId) {}

    public record EffectiveValue(String key, String label, String unit, String kind, String category, String value,
                                 Source source) {}

    /**
     * Giá trị suy ra — Admin không tự nhập (AC-14).
     *
     * @param maxHoursFormula ví dụ "60 × 125% = 75"
     */
    public record Derived(BigDecimal targetHours, String maxHoursBasis, BigDecimal maxHours, String maxHoursFormula,
                          BigDecimal warningHours) {}

    public record Missing(String key, String label, String reason) {}

    public record LevelSubstitution(String higherLevelId, String lowerLevelId) {}

    /**
     * @param confirmed {@code false} khi bảng level_substitutions vẫn là dữ liệu mẫu V5 chưa được Admin xác nhận
     */
    public record LevelSubstitutionsInfo(List<LevelSubstitution> pairs, boolean confirmed, String note) {}

    public record ScopeContext(String userId, String branchId, String employeeTypeId, String groupId, String note) {}

    public record EffectiveRules(String userId, LocalDate date, ScopeContext scopeContext, List<EffectiveValue> values,
                                 Derived derived, List<Missing> missing, List<String> issues,
                                 boolean readyForScheduling, LevelSubstitutionsInfo levelSubstitutions) {}

    public record UserReadiness(String userId, String employeeCode, String fullName, List<Missing> missing,
                                List<String> issues) {}

    public record Readiness(String periodId, LocalDate evaluatedAt, int applicable, int ready,
                            List<UserReadiness> notReady) {}

    public record Template(String note, List<ChangeItem> items) {}
}
