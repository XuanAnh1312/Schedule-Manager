package org.dacnpm.schedulemanager.api.availability;

import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.MyAvailability;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Progress;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Registration;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.SaveRequest;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Summary;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Đăng ký thời gian rảnh (issue #5, TU-04). Người dùng lấy từ JWT, không nhận userId từ client.
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

    private final AvailabilityService service;

    public AvailabilityController(AvailabilityService service) {
        this.service = service;
    }

    /** Nhân viên: đăng ký của mình trong kỳ, kèm {@code editable} tính theo giờ server. */
    @GetMapping("/my")
    public MyAvailability getMine(@RequestParam String periodId) {
        return service.getMine(CurrentUser.require(), periodId);
    }

    /** Nhân viên: lưu (nháp hoặc sửa bản đã gửi). Bị từ chối 409 khi kỳ đã đóng. */
    @PutMapping("/my")
    public MyAvailability saveMine(@RequestParam String periodId, @RequestBody SaveRequest request) {
        return service.saveMine(CurrentUser.require(), periodId, request);
    }

    /** Nhân viên: gửi đăng ký. Bị từ chối 409 khi kỳ đã đóng, kể cả form đã mở trước hạn. */
    @PostMapping("/my/submit")
    public MyAvailability submitMine(@RequestParam String periodId, @RequestBody SaveRequest request) {
        return service.submitMine(CurrentUser.require(), periodId, request);
    }

    /** Admin: ai đã gửi / đang nháp / chưa đăng ký. */
    @GetMapping("/progress")
    public Progress progress(@RequestParam String periodId) {
        return service.progress(CurrentUser.require(), periodId);
    }

    /** Admin: tổng hợp khả năng đáp ứng theo ngày. */
    @GetMapping("/summary")
    public Summary summary(@RequestParam String periodId) {
        return service.summary(CurrentUser.require(), periodId);
    }

    /** Admin: chi tiết các đăng ký đã gửi (nguồn dữ liệu cho phân lịch). */
    @GetMapping("/submitted")
    public List<Registration> submitted(@RequestParam String periodId) {
        return service.submitted(CurrentUser.require(), periodId);
    }
}
