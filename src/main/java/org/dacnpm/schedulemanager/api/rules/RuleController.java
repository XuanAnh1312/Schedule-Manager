package org.dacnpm.schedulemanager.api.rules;

import org.dacnpm.schedulemanager.api.rules.RuleModels.ApplyRequest;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Catalog;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeRequest;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeView;
import org.dacnpm.schedulemanager.api.rules.RuleModels.EffectiveRules;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Preview;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Readiness;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Setting;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Template;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Cài đặt quy tắc (issue #5, TU-05 … TU-07). Ghi chỉ dành cho admin; nhân viên chỉ xem
 * danh mục và quy tắc áp dụng cho chính mình.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleService service;

    public RuleController(RuleService service) {
        this.service = service;
    }

    /** Danh mục tham số: ý nghĩa, đơn vị, khoảng hợp lệ, phạm vi, thứ tự ưu tiên. */
    @GetMapping("/catalog")
    public Catalog catalog() {
        CurrentUser.require();
        return service.catalog();
    }

    /** Admin: giá trị đã lưu (mặc định chỉ active). */
    @GetMapping("/settings")
    public List<Setting> settings(@RequestParam(required = false) String scopeType,
                                  @RequestParam(required = false) String scopeId,
                                  @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.settings(CurrentUser.require(), scopeType, scopeId, includeInactive);
    }

    /** Quy tắc có hiệu lực + nguồn áp dụng + giá trị suy ra + checklist thiếu cấu hình. */
    @GetMapping("/effective")
    public EffectiveRules effective(@RequestParam(required = false) String userId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.effective(CurrentUser.require(), userId, date);
    }

    /** Admin: nhân viên của kỳ còn thiếu cấu hình bắt buộc (kiểm tra trước khi xếp lịch). */
    @GetMapping("/readiness")
    public Readiness readiness(@RequestParam String periodId) {
        return service.readiness(CurrentUser.require(), periodId);
    }

    /** Admin: giá trị ĐỀ XUẤT từ dữ liệu mẫu V5, chưa có hiệu lực. */
    @GetMapping("/templates")
    public Template templates() {
        return service.templates(CurrentUser.require());
    }

    /** Admin: kiểm tra + xem trước tác động. Chưa thay đổi gì. */
    @PostMapping("/changes")
    public ResponseEntity<Preview> preview(@RequestBody ChangeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.preview(CurrentUser.require(), request));
    }

    /** Admin: lịch sử thay đổi. status = applied (mặc định) | previewed | cancelled | all. */
    @GetMapping("/changes")
    public List<ChangeView> history(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) Integer limit) {
        return service.history(CurrentUser.require(), status, limit);
    }

    @GetMapping("/changes/{id}")
    public ChangeView change(@PathVariable String id) {
        return service.change(CurrentUser.require(), id);
    }

    /** Admin: áp dụng bản xem trước. Body: {"confirm": true}. */
    @PostMapping("/changes/{id}/apply")
    public ChangeView apply(@PathVariable String id, @RequestBody(required = false) ApplyRequest request) {
        return service.apply(CurrentUser.require(), id, request);
    }

    @PostMapping("/changes/{id}/cancel")
    public ChangeView cancel(@PathVariable String id) {
        return service.cancel(CurrentUser.require(), id);
    }
}
