package org.dacnpm.schedulemanager.api.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dacnpm.schedulemanager.api.periods.PeriodRepository;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ApplyRequest;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Catalog;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeItem;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeRequest;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeView;
import org.dacnpm.schedulemanager.api.rules.RuleModels.EffectiveRules;
import org.dacnpm.schedulemanager.api.rules.RuleModels.LevelSubstitution;
import org.dacnpm.schedulemanager.api.rules.RuleModels.LevelSubstitutionsInfo;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ParameterInfo;
import org.dacnpm.schedulemanager.api.rules.RuleModels.PeriodImpact;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Preview;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Readiness;
import org.dacnpm.schedulemanager.api.rules.RuleModels.RelatedSetting;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ScopeContext;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ScopeInfo;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Setting;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Template;
import org.dacnpm.schedulemanager.api.rules.RuleModels.UserChange;
import org.dacnpm.schedulemanager.api.rules.RuleModels.UserReadiness;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ValueChange;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.BusinessTime;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Cài đặt quy tắc (TU-05), quy tắc có hiệu lực (TU-06) và áp dụng cấu hình (TU-07).
 *
 * <p>Luồng thay đổi: {@code POST /changes} (kiểm tra + xem trước tác động, chưa đổi gì) →
 * {@code POST /changes/{id}/apply {"confirm": true}} (ghi giá trị mới, giữ bản cũ ở trạng thái
 * superseded/removed, ghi history). Áp dụng quy tắc không sửa kỳ, ca hay phân công đã có.
 */
@Profile("sqlserver")
@Service
public class RuleService {

    private static final int MAX_ITEMS = 50;
    private static final int MAX_REASON_LENGTH = 1000;
    private static final int MAX_USER_CHANGES_IN_PREVIEW = 100;
    private static final String SCOPE_NOTE =
            "Chưa có dữ liệu chi nhánh/loại nhân viên/nhóm (A-04); hiện chỉ áp dụng phạm vi công ty và cá nhân.";

    private final RuleRepository repository;
    private final PeriodRepository periods;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;

    public RuleService(RuleRepository repository, PeriodRepository periods, AuditTrail audit,
                       ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.periods = periods;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /* ── Danh mục / đọc ──────────────────────────────────────────────────── */

    public Catalog catalog() {
        List<ParameterInfo> parameters = Arrays.stream(RuleParameter.values()).map(p -> new ParameterInfo(
                p.key(), p.label(), p.unit(), p.description(), p.type().name().toLowerCase(Locale.ROOT),
                p.min(), p.max(), p.allowedValues(), p.kind().name().toLowerCase(Locale.ROOT),
                p.category().name().toLowerCase(Locale.ROOT), p.category().priority(), p.requiredForScheduling(),
                requiredWhen(p), p.companyOnly(), p.immediateOnly())).toList();
        List<ScopeInfo> scopes = Arrays.stream(RuleScopeType.values()).map(s -> new ScopeInfo(s.dbValue(),
                s.precedence(), s.available(), s.available() ? null : "Chờ danh mục tổ chức (A-04).")).toList();
        List<String> conflictOrder = List.of(
                "1. Ràng buộc pháp luật/an toàn bắt buộc (chưa có dữ liệu trong V1)",
                "2. Điều kiện hợp đồng/chứng chỉ (HR-02, LS-03 — chưa có dữ liệu trong V1)",
                "3. Quy tắc doanh nghiệp (giới hạn giờ, nghỉ giữa ca, ngày liên tiếp, Level thay thế)",
                "4. Tiêu chí ưu tiên của thuật toán (giờ mục tiêu, trọng số)",
                "Cùng tham số: user > group > employee_type > branch > company; cùng phạm vi: ngày hiệu lực mới nhất");
        List<RelatedSetting> related = List.of(
                new RelatedSetting("Hạn đăng ký, tự khóa, mở lại", "/api/periods", "Cấu hình theo từng kỳ."),
                new RelatedSetting("Mốc nhắc trước hạn đăng ký", "/api/notifications/settings",
                        "Module thông báo (BA-05) quản lý, không lưu trùng ở đây."),
                new RelatedSetting("Loại yêu cầu cần duyệt và người nhận thông báo", "Module yêu cầu/thông báo",
                        "BA-01 … BA-06 quản lý."));
        return new Catalog(parameters, scopes, conflictOrder, related);
    }

    public List<Setting> settings(CurrentUser me, String scopeType, String scopeId, boolean includeInactive) {
        me.requireAdmin();
        if (scopeType != null && RuleScopeType.fromDb(scopeType).isEmpty()) {
            throw ApiException.badRequest("INVALID_SCOPE", "scopeType không hợp lệ.");
        }
        return repository.findSettings(scopeType, blankToNull(scopeId), includeInactive);
    }

    /** Admin xem cho bất kỳ nhân viên nào; nhân viên chỉ xem của chính mình. */
    public EffectiveRules effective(CurrentUser me, String userId, LocalDate date) {
        String target = blankToNull(userId) == null ? me.id() : userId.trim();
        if (!me.admin() && !target.equals(me.id())) {
            throw ApiException.forbidden("Bạn chỉ xem được quy tắc áp dụng cho chính mình.");
        }
        RuleRepository.EmployeeRow employee = repository.findUser(target)
                .filter(u -> "user".equals(u.role()))
                .orElseThrow(() -> ApiException.notFound("nhân viên"));
        LocalDate at = date == null ? BusinessTime.today(clock) : date;
        return EffectiveRuleResolver.resolve(repository.findActiveSettings(), contextOf(employee.id()), at,
                levelSubstitutionsInfo());
    }

    /** Checklist trước khi chạy phân lịch: nhân viên nào thuộc kỳ còn thiếu cấu hình bắt buộc. */
    public Readiness readiness(CurrentUser me, String periodId) {
        me.requireAdmin();
        RuleRepository.PeriodRow period = repository.findPeriod(periodId)
                .orElseThrow(() -> ApiException.notFound("kỳ đăng ký"));
        LocalDate at = period.startDate();
        List<Setting> settings = repository.findActiveSettings();
        LevelSubstitutionsInfo levels = levelSubstitutionsInfo();
        List<PeriodRepository.Employee> employees = periods.findApplicableEmployees(period.id());
        List<UserReadiness> notReady = new ArrayList<>();
        for (PeriodRepository.Employee e : employees) {
            EffectiveRules rules = EffectiveRuleResolver.resolve(settings, contextOf(e.id()), at, levels);
            if (!rules.readyForScheduling()) {
                notReady.add(new UserReadiness(e.id(), e.employeeCode(), e.fullName(), rules.missing(), rules.issues()));
            }
        }
        return new Readiness(period.id(), at, employees.size(), employees.size() - notReady.size(), notReady);
    }

    /**
     * Giá trị đề xuất từ dữ liệu mẫu V5 — chưa có hiệu lực. Admin phải gửi chúng qua xem trước và xác nhận.
     */
    public Template templates(CurrentUser me) {
        me.requireAdmin();
        List<ChangeItem> items = new ArrayList<>();
        repository.findTemplate().ifPresent(t -> {
            items.add(suggest(RuleParameter.TARGET_HOURS, t.standardHours()));
            items.add(new ChangeItem("set", RuleParameter.MAX_HOURS_MODE.key(), "company", null, "fixed", null));
            items.add(suggest(RuleParameter.MAX_HOURS_FIXED, t.maxHoursPerPeriod()));
            items.add(suggest(RuleParameter.MAX_HOURS_PER_DAY, t.maxHoursPerDay()));
            items.add(suggest(RuleParameter.MAX_HOURS_PER_WEEK, t.maxHoursPerWeek()));
            items.add(suggest(RuleParameter.MIN_REST_HOURS, t.minRestBetweenShifts()));
        });
        List<LevelSubstitution> pairs = repository.findLevelSubstitutions();
        if (!pairs.isEmpty()) {
            items.add(new ChangeItem("set", RuleParameter.LEVEL_SUBSTITUTIONS.key(), "company", null, toJson(pairs), null));
        }
        return new Template("ĐỀ XUẤT từ dữ liệu mẫu V5, chưa có hiệu lực. Admin xem lại, chỉnh sửa rồi gửi qua "
                + "POST /api/rules/changes và xác nhận. Không có đề xuất cho số ngày làm liên tiếp tối đa — "
                + "Admin phải tự nhập.", items);
    }

    /* ── Xem trước ───────────────────────────────────────────────────────── */

    @Transactional
    public Preview preview(CurrentUser me, ChangeRequest request) {
        me.requireAdmin();
        if (request == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Thiếu nội dung thay đổi.");
        }
        String reason = blankToNull(request.reason());
        List<String> errors = new ArrayList<>();
        if (reason == null) errors.add("reason: bắt buộc nhập lý do thay đổi.");
        else if (reason.length() > MAX_REASON_LENGTH) errors.add("reason: tối đa " + MAX_REASON_LENGTH + " ký tự.");
        List<ChangeItem> items = normalizeItems(request.items(), errors);
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Thay đổi quy tắc không hợp lệ.", errors);
        }

        List<Setting> before = repository.findActiveSettings();
        requireRemovableTargetsExist(items, before);
        List<Setting> after = simulate(before, items);
        String changeId = Ids.next("rchg");
        Preview preview = buildPreview(changeId, repository.currentRevision(), reason, items, before, after);
        repository.insertChange(changeId, preview.baseRevision(), reason, toJson(items), toJson(preview), me.id());
        return preview;
    }

    /* ── Áp dụng / hủy ───────────────────────────────────────────────────── */

    @Transactional
    public ChangeView apply(CurrentUser me, String changeId, ApplyRequest request) {
        me.requireAdmin();
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            throw ApiException.badRequest("CONFIRM_REQUIRED", "Cần xác nhận (confirm = true) trước khi áp dụng.");
        }
        RuleRepository.ChangeRow change = repository.findChangeForUpdate(changeId)
                .orElseThrow(() -> ApiException.notFound("thay đổi quy tắc"));
        if (!"previewed".equals(change.status())) {
            throw ApiException.conflict("CHANGE_NOT_PENDING", "Thay đổi này đã " + change.status() + ".");
        }
        List<ChangeItem> items = readItems(change.itemsJson());
        LocalDate today = BusinessTime.today(clock);
        for (ChangeItem item : items) {
            if (item.effectiveFrom().isBefore(today)) {
                throw ApiException.conflict("PREVIEW_EXPIRED",
                        "Ngày hiệu lực đã ở quá khứ. Hãy xem trước lại với ngày hiệu lực mới.");
            }
        }
        if (!repository.bumpRevision(change.baseRevision())) {
            throw ApiException.conflict("STALE_PREVIEW",
                    "Quy tắc đã được thay đổi sau lần xem trước này. Hãy xem trước lại.");
        }

        List<Setting> beforeSettings = repository.findActiveSettings();
        List<Map<String, Object>> beforeAudit = new ArrayList<>();
        List<Map<String, Object>> afterAudit = new ArrayList<>();
        for (ChangeItem item : items) {
            beforeAudit.add(auditEntry(item, currentValue(beforeSettings, item)));
            if ("remove".equals(item.op())) {
                if (repository.removeActive(item.paramKey(), item.scopeType(), item.scopeId(), changeId) == 0) {
                    throw ApiException.conflict("STALE_PREVIEW", "Giá trị cần xóa không còn tồn tại. Hãy xem trước lại.");
                }
                afterAudit.add(auditEntry(item, null));
                continue;
            }
            repository.supersede(item.paramKey(), item.scopeType(), item.scopeId(), item.effectiveFrom(), changeId);
            repository.insertSetting(Ids.next("rule"), item.paramKey(), item.scopeType(), item.scopeId(), item.value(),
                    item.effectiveFrom(), changeId, me.id());
            if (RuleParameter.LEVEL_SUBSTITUTIONS.key().equals(item.paramKey())) {
                if (!repository.mainRulesRowExists()) {
                    throw ApiException.conflict("MAIN_RULES_MISSING", "Thiếu bản ghi schedule_rules 'rules_main'.");
                }
                repository.replaceLevelSubstitutions(readPairs(item.value()));
            }
            afterAudit.add(auditEntry(item, item.value()));
        }
        repository.markApplied(changeId, me.id());
        audit.record("rules.applied", "Áp dụng thay đổi quy tắc", items.size() + " thay đổi", me.id(),
                beforeAudit, afterAudit, change.reason());
        return toView(repository.findChange(changeId).orElseThrow());
    }

    @Transactional
    public ChangeView cancel(CurrentUser me, String changeId) {
        me.requireAdmin();
        RuleRepository.ChangeRow change = repository.findChangeForUpdate(changeId)
                .orElseThrow(() -> ApiException.notFound("thay đổi quy tắc"));
        if (!"previewed".equals(change.status())) {
            throw ApiException.conflict("CHANGE_NOT_PENDING", "Thay đổi này đã " + change.status() + ".");
        }
        repository.markCancelled(changeId);
        return toView(repository.findChange(changeId).orElseThrow());
    }

    public ChangeView change(CurrentUser me, String changeId) {
        me.requireAdmin();
        return toView(repository.findChange(changeId).orElseThrow(() -> ApiException.notFound("thay đổi quy tắc")));
    }

    /** Lịch sử thay đổi (mặc định các thay đổi đã áp dụng), mới nhất trước. */
    public List<ChangeView> history(CurrentUser me, String status, Integer limit) {
        me.requireAdmin();
        String filter = status == null ? "applied" : ("all".equals(status) ? null : status);
        if (filter != null && !Set.of("previewed", "applied", "cancelled").contains(filter)) {
            throw ApiException.badRequest("INVALID_STATUS", "status phải là previewed, applied, cancelled hoặc all.");
        }
        int top = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        return repository.listChanges(filter, top).stream().map(this::toView).toList();
    }

    /* ── Kiểm tra thay đổi ───────────────────────────────────────────────── */

    private List<ChangeItem> normalizeItems(List<ChangeItem> input, List<String> errors) {
        if (input == null || input.isEmpty()) {
            errors.add("items: cần ít nhất một thay đổi.");
            return List.of();
        }
        if (input.size() > MAX_ITEMS) {
            errors.add("items: tối đa " + MAX_ITEMS + " thay đổi mỗi lần.");
            return List.of();
        }
        LocalDate today = BusinessTime.today(clock);
        Set<String> levelIds = null;
        Set<String> seen = new HashSet<>();
        List<ChangeItem> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            ChangeItem in = input.get(i);
            String at = "items[" + i + "]";
            if (in == null) {
                errors.add(at + ": trống.");
                continue;
            }
            String op = in.op() == null ? "set" : in.op().trim().toLowerCase(Locale.ROOT);
            if (!op.equals("set") && !op.equals("remove")) {
                errors.add(at + ".op: phải là set hoặc remove.");
                continue;
            }
            RuleParameter p = RuleParameter.byKey(in.paramKey() == null ? "" : in.paramKey().trim()).orElse(null);
            if (p == null) {
                errors.add(at + ".paramKey: không có trong danh mục (GET /api/rules/catalog).");
                continue;
            }
            RuleScopeType scope = RuleScopeType.fromDb(in.scopeType() == null ? "company" : in.scopeType().trim())
                    .orElse(null);
            if (scope == null) {
                errors.add(at + ".scopeType: không hợp lệ.");
                continue;
            }
            if (!scope.available()) {
                errors.add(at + ".scopeType: phạm vi " + scope.dbValue() + " chưa có danh mục tổ chức (A-04).");
                continue;
            }
            if (p.companyOnly() && scope != RuleScopeType.COMPANY) {
                errors.add(at + ": " + p.key() + " chỉ cấu hình ở phạm vi company.");
                continue;
            }
            String scopeId = scope == RuleScopeType.COMPANY ? null : blankToNull(in.scopeId());
            if (scope == RuleScopeType.USER) {
                if (scopeId == null) {
                    errors.add(at + ".scopeId: bắt buộc với phạm vi user.");
                    continue;
                }
                boolean employee = repository.findUser(scopeId).map(u -> "user".equals(u.role())).orElse(false);
                if (!employee) {
                    errors.add(at + ".scopeId: không phải nhân viên.");
                    continue;
                }
            }
            LocalDate effectiveFrom = in.effectiveFrom() == null ? today : in.effectiveFrom();
            if (effectiveFrom.isBefore(today)) {
                errors.add(at + ".effectiveFrom: không được ở quá khứ (không sửa ngược kỳ đã qua).");
                continue;
            }
            if ((p.immediateOnly() || op.equals("remove")) && !effectiveFrom.equals(today)) {
                errors.add(at + ".effectiveFrom: " + (op.equals("remove") ? "thao tác remove" : p.key())
                        + " chỉ có hiệu lực ngay (hôm nay).");
                continue;
            }
            String value = null;
            if (op.equals("set")) {
                if (p.type() == RuleParameter.ValueType.LEVEL_PAIRS && levelIds == null) {
                    levelIds = repository.findLevelIds();
                }
                value = normalizeValue(p, in.value(), levelIds, at, errors);
                if (value == null) continue;
            }
            String key = p.key() + "|" + scope.dbValue() + "|" + scopeId + "|" + (op.equals("remove") ? "*" : effectiveFrom);
            if (!seen.add(key)) {
                errors.add(at + ": trùng tham số/phạm vi/ngày hiệu lực với thay đổi khác trong cùng lần gửi.");
                continue;
            }
            result.add(new ChangeItem(op, p.key(), scope.dbValue(), scopeId, value, effectiveFrom));
        }
        return result;
    }

    /** Chuẩn hóa giá trị về chuỗi lưu DB; trả null và ghi lỗi nếu không hợp lệ. */
    String normalizeValue(RuleParameter p, String raw, Set<String> levelIds, String at, List<String> errors) {
        String value = blankToNull(raw);
        if (value == null) {
            errors.add(at + ".value: bắt buộc với op set.");
            return null;
        }
        switch (p.type()) {
            case ENUM -> {
                String v = value.toLowerCase(Locale.ROOT);
                if (!p.allowedValues().contains(v)) {
                    errors.add(at + ".value: " + p.key() + " phải là " + String.join(" hoặc ", p.allowedValues()) + ".");
                    return null;
                }
                return v;
            }
            case DECIMAL, INTEGER -> {
                BigDecimal number;
                try {
                    number = new BigDecimal(value);
                } catch (NumberFormatException e) {
                    errors.add(at + ".value: " + p.key() + " phải là số.");
                    return null;
                }
                if (p.type() == RuleParameter.ValueType.INTEGER && number.stripTrailingZeros().scale() > 0) {
                    errors.add(at + ".value: " + p.key() + " phải là số nguyên.");
                    return null;
                }
                if (number.stripTrailingZeros().scale() > 2) {
                    errors.add(at + ".value: " + p.key() + " tối đa 2 chữ số thập phân.");
                    return null;
                }
                if ((p.min() != null && number.compareTo(p.min()) < 0) || (p.max() != null && number.compareTo(p.max()) > 0)) {
                    errors.add(at + ".value: " + p.key() + " phải trong khoảng " + EffectiveRuleResolver.plain(p.min())
                            + "–" + EffectiveRuleResolver.plain(p.max()) + (p.unit() == null ? "" : " " + p.unit()) + ".");
                    return null;
                }
                return EffectiveRuleResolver.plain(number);
            }
            case LEVEL_PAIRS -> {
                List<LevelSubstitution> pairs;
                try {
                    pairs = json.readValue(value, new TypeReference<List<LevelSubstitution>>() {});
                } catch (JsonProcessingException e) {
                    errors.add(at + ".value: level_substitutions phải là JSON [{\"higherLevelId\":\"lvl4\",\"lowerLevelId\":\"lvl3\"}].");
                    return null;
                }
                Set<String> unique = new LinkedHashSet<>();
                List<LevelSubstitution> clean = new ArrayList<>();
                for (LevelSubstitution pair : pairs) {
                    if (pair == null || pair.higherLevelId() == null || pair.lowerLevelId() == null
                            || !levelIds.contains(pair.higherLevelId()) || !levelIds.contains(pair.lowerLevelId())) {
                        errors.add(at + ".value: Level không tồn tại trong cặp " + pair + ".");
                        return null;
                    }
                    if (pair.higherLevelId().equals(pair.lowerLevelId())) {
                        errors.add(at + ".value: một Level không thể thay chính nó.");
                        return null;
                    }
                    if (unique.add(pair.higherLevelId() + ">" + pair.lowerLevelId())) clean.add(pair);
                }
                clean.sort((a, b) -> (a.higherLevelId() + a.lowerLevelId()).compareTo(b.higherLevelId() + b.lowerLevelId()));
                return toJson(clean);
            }
            default -> throw new IllegalStateException("Unhandled value type " + p.type());
        }
    }

    private void requireRemovableTargetsExist(List<ChangeItem> items, List<Setting> active) {
        List<String> errors = new ArrayList<>();
        for (ChangeItem item : items) {
            if ("remove".equals(item.op()) && active.stream().noneMatch(s -> sameTarget(s, item))) {
                errors.add(item.paramKey() + " (" + item.scopeType() + (item.scopeId() == null ? "" : " " + item.scopeId())
                        + "): chưa có giá trị để xóa.");
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Thay đổi quy tắc không hợp lệ.", errors);
        }
    }

    /* ── Tính tác động ───────────────────────────────────────────────────── */

    /** Giá trị active sau khi áp dụng thử các thay đổi (chỉ trong bộ nhớ). */
    static List<Setting> simulate(List<Setting> before, List<ChangeItem> items) {
        List<Setting> after = new ArrayList<>(before);
        for (ChangeItem item : items) {
            if ("remove".equals(item.op())) {
                after.removeIf(s -> sameTarget(s, item));
            } else {
                after.removeIf(s -> sameTarget(s, item) && s.effectiveFrom().equals(item.effectiveFrom()));
                after.add(new Setting("preview", item.paramKey(), item.scopeType(), item.scopeId(), item.value(),
                        item.effectiveFrom(), "active", "preview", null, null));
            }
        }
        return after;
    }

    private Preview buildPreview(String changeId, int revision, String reason, List<ChangeItem> items,
                                 List<Setting> before, List<Setting> after) {
        LocalDate evaluatedAt = items.stream().map(ChangeItem::effectiveFrom).max(LocalDate::compareTo).orElseThrow();
        LocalDate earliest = items.stream().map(ChangeItem::effectiveFrom).min(LocalDate::compareTo).orElseThrow();

        List<ValueChange> companyChanges = new ArrayList<>();
        for (ChangeItem item : items) {
            RuleParameter p = RuleParameter.byKey(item.paramKey()).orElseThrow();
            if (p.companyOnly()) {
                String old = p == RuleParameter.LEVEL_SUBSTITUTIONS
                        ? toJson(repository.findLevelSubstitutions()) : currentValue(before, item);
                companyChanges.add(new ValueChange(p.key(), old, item.value()));
            }
        }

        boolean companyWide = items.stream().anyMatch(i -> "company".equals(i.scopeType())
                && !RuleParameter.byKey(i.paramKey()).orElseThrow().companyOnly());
        Set<String> targetUsers = new HashSet<>();
        items.stream().filter(i -> "user".equals(i.scopeType())).forEach(i -> targetUsers.add(i.scopeId()));
        List<RuleRepository.EmployeeRow> employees = repository.findActiveEmployees().stream()
                .filter(e -> companyWide || targetUsers.contains(e.id())).toList();

        LevelSubstitutionsInfo levels = levelSubstitutionsInfo();
        List<UserChange> userChanges = new ArrayList<>();
        int affected = 0;
        int notReadyAfter = 0;
        int becameNotReady = 0;
        int maxBelowTarget = 0;
        for (RuleRepository.EmployeeRow e : employees) {
            EffectiveRules b = EffectiveRuleResolver.resolve(before, contextOf(e.id()), evaluatedAt, levels);
            EffectiveRules a = EffectiveRuleResolver.resolve(after, contextOf(e.id()), evaluatedAt, levels);
            if (!a.readyForScheduling()) notReadyAfter++;
            if (b.readyForScheduling() && !a.readyForScheduling()) becameNotReady++;
            if (a.issues().stream().anyMatch(s -> s.startsWith("MAX_BELOW_TARGET"))) maxBelowTarget++;
            List<ValueChange> diff = diff(b, a);
            if (!diff.isEmpty()) {
                affected++;
                if (userChanges.size() < MAX_USER_CHANGES_IN_PREVIEW) {
                    userChanges.add(new UserChange(e.id(), e.employeeCode(), e.fullName(), diff,
                            b.readyForScheduling(), a.readyForScheduling()));
                }
            }
        }

        List<PeriodImpact> periodImpacts = repository.findPeriodsEndingOnOrAfter(earliest).stream()
                .map(p -> new PeriodImpact(p.id(), p.name(), p.status(), p.startDate(), p.endDate(),
                        "open".equals(p.status())
                                ? "Áp dụng cho các lần xếp lịch tiếp theo của kỳ này."
                                : "Kỳ đã đóng/lưu trữ: lịch và phân công hiện có giữ nguyên, không bị sửa tự động. "
                                + "Chỉ khi người có quyền chạy lại phân lịch mới dùng quy tắc mới."))
                .toList();

        List<String> warnings = new ArrayList<>();
        for (ChangeItem item : items) {
            if (RuleParameter.MAX_HOURS_PERCENT.key().equals(item.paramKey()) && item.value() != null
                    && new BigDecimal(item.value()).compareTo(BigDecimal.valueOf(100)) < 0) {
                warnings.add("max_hours_percent < 100%: giờ tối đa sẽ nhỏ hơn giờ mục tiêu.");
            }
        }
        if (maxBelowTarget > 0) warnings.add(maxBelowTarget + " nhân viên có giờ tối đa nhỏ hơn giờ mục tiêu sau thay đổi.");
        if (becameNotReady > 0) warnings.add(becameNotReady + " nhân viên sẽ chuyển sang thiếu cấu hình bắt buộc.");
        if (notReadyAfter > 0) warnings.add(notReadyAfter + " nhân viên vẫn chưa đủ cấu hình bắt buộc để xếp lịch tự động.");

        return new Preview(changeId, "previewed", revision, reason, evaluatedAt, items, companyChanges, affected,
                userChanges, notReadyAfter, periodImpacts, warnings,
                "Chưa có thay đổi nào được lưu. Gọi apply với confirm = true để áp dụng.");
    }

    private static List<ValueChange> diff(EffectiveRules before, EffectiveRules after) {
        Map<String, String> b = new LinkedHashMap<>();
        Map<String, String> a = new LinkedHashMap<>();
        before.values().forEach(v -> b.put(v.key(), v.value()));
        after.values().forEach(v -> a.put(v.key(), v.value()));
        List<ValueChange> changes = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(b.keySet());
        keys.addAll(a.keySet());
        for (String key : keys) {
            if (RuleParameter.byKey(key).map(RuleParameter::companyOnly).orElse(false)) continue;
            if (!Objects.equals(b.get(key), a.get(key))) changes.add(new ValueChange(key, b.get(key), a.get(key)));
        }
        String maxBefore = before.derived().maxHours() == null ? null : EffectiveRuleResolver.plain(before.derived().maxHours());
        String maxAfter = after.derived().maxHours() == null ? null : EffectiveRuleResolver.plain(after.derived().maxHours());
        if (!Objects.equals(maxBefore, maxAfter)) changes.add(new ValueChange("derived.max_hours", maxBefore, maxAfter));
        return changes;
    }

    /* ── Hỗ trợ ──────────────────────────────────────────────────────────── */

    private LevelSubstitutionsInfo levelSubstitutionsInfo() {
        boolean confirmed = repository.hasAppliedSetting(RuleParameter.LEVEL_SUBSTITUTIONS.key());
        return new LevelSubstitutionsInfo(repository.findLevelSubstitutions(), confirmed, confirmed ? null
                : "Đang dùng dữ liệu mẫu V5 chưa được Admin xác nhận. Xem /api/rules/templates để xác nhận.");
    }

    /** Hiện chỉ có userId; chi nhánh/loại nhân viên/nhóm bổ sung khi có danh mục (A-04). */
    private static ScopeContext contextOf(String userId) {
        return new ScopeContext(userId, null, null, null, SCOPE_NOTE);
    }

    private static boolean sameTarget(Setting s, ChangeItem item) {
        return s.paramKey().equals(item.paramKey()) && s.scopeType().equals(item.scopeType())
                && Objects.equals(s.scopeId(), item.scopeId());
    }

    /** Giá trị active hiện tại cho đúng tham số/phạm vi (ngày hiệu lực gần nhất), hoặc null. */
    private static String currentValue(List<Setting> settings, ChangeItem item) {
        return settings.stream().filter(s -> sameTarget(s, item))
                .filter(s -> !s.effectiveFrom().isAfter(item.effectiveFrom()))
                .max((x, y) -> x.effectiveFrom().compareTo(y.effectiveFrom()))
                .map(Setting::value).orElse(null);
    }

    private static Map<String, Object> auditEntry(ChangeItem item, String value) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("op", item.op());
        entry.put("paramKey", item.paramKey());
        entry.put("scopeType", item.scopeType());
        entry.put("scopeId", item.scopeId());
        entry.put("effectiveFrom", item.effectiveFrom().toString());
        entry.put("value", value);
        return entry;
    }

    private static ChangeItem suggest(RuleParameter p, BigDecimal value) {
        return new ChangeItem("set", p.key(), "company", null, value == null ? null : EffectiveRuleResolver.plain(value), null);
    }

    private static String requiredWhen(RuleParameter p) {
        return switch (p) {
            case MAX_HOURS_FIXED -> "max_hours_mode = fixed";
            case MAX_HOURS_PERCENT -> "max_hours_mode = percent";
            default -> p.requiredForScheduling() ? "luôn bắt buộc trước khi xếp lịch tự động" : null;
        };
    }

    private ChangeView toView(RuleRepository.ChangeRow row) {
        JsonNode preview;
        try {
            preview = row.previewJson() == null ? null : json.readTree(row.previewJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt preview_json for rule change " + row.id(), e);
        }
        return new ChangeView(row.id(), row.status(), row.reason(), row.baseRevision(), readItems(row.itemsJson()),
                preview, row.createdBy(), BusinessTime.toLocal(row.createdAtUtc(), clock.getZone()), row.appliedBy(),
                BusinessTime.toLocal(row.appliedAtUtc(), clock.getZone()));
    }

    private List<ChangeItem> readItems(String itemsJson) {
        try {
            return json.readValue(itemsJson, new TypeReference<List<ChangeItem>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt items_json in rule_changes", e);
        }
    }

    private List<LevelSubstitution> readPairs(String value) {
        try {
            return json.readValue(value, new TypeReference<List<LevelSubstitution>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt level_substitutions value", e);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize rules JSON", e);
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Số giờ tối đa đang có hiệu lực của nhân viên — dùng cho bộ xếp lịch (TW-03) qua service nội bộ. */
    public EffectiveRules effectiveForScheduling(String userId, LocalDate date) {
        return EffectiveRuleResolver.resolve(repository.findActiveSettings(), contextOf(userId), date,
                levelSubstitutionsInfo());
    }
}
