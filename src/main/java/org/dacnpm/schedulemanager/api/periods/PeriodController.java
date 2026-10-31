package org.dacnpm.schedulemanager.api.periods;

import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Kỳ đăng ký (issue #5, TU-01 … TU-03). Cần JWT; thao tác ghi chỉ dành cho admin
 * (kiểm tra trong service, không tin role/userId do client gửi).
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/periods")
public class PeriodController {

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** Admin: mọi kỳ. Nhân viên: kỳ đã công bố và áp dụng cho mình. */
    @GetMapping
    public List<PeriodView> list() {
        return service.list(CurrentUser.require());
    }

    @GetMapping("/{id}")
    public PeriodView get(@PathVariable String id) {
        return service.get(CurrentUser.require(), id);
    }

    @PostMapping
    public ResponseEntity<PeriodView> create(@RequestBody PeriodRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(CurrentUser.require(), request));
    }

    @PutMapping("/{id}")
    public PeriodView update(@PathVariable String id, @RequestBody PeriodRequest request) {
        return service.update(CurrentUser.require(), id, request);
    }

    /** draft → open. */
    @PostMapping("/{id}/open")
    public PeriodView open(@PathVariable String id, @RequestBody(required = false) PeriodActionRequest body) {
        return service.open(CurrentUser.require(), id, body);
    }

    /** open → locked (đóng thủ công). */
    @PostMapping("/{id}/lock")
    public PeriodView lock(@PathVariable String id, @RequestBody(required = false) PeriodActionRequest body) {
        return service.lock(CurrentUser.require(), id, body);
    }

    /** locked → open. Body: {"confirm": true, "reason": "...", "newDeadline": "2026-11-10T23:59"}. */
    @PostMapping("/{id}/reopen")
    public PeriodView reopen(@PathVariable String id, @RequestBody(required = false) PeriodActionRequest body) {
        return service.reopen(CurrentUser.require(), id, body);
    }

    /** locked → archived. */
    @PostMapping("/{id}/archive")
    public PeriodView archive(@PathVariable String id, @RequestBody(required = false) PeriodActionRequest body) {
        return service.archive(CurrentUser.require(), id, body);
    }

    /** Chỉ xóa kỳ nháp. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }
}
