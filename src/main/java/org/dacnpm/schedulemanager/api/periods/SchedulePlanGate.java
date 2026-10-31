package org.dacnpm.schedulemanager.api.periods;

import java.util.List;

/**
 * Điểm nối với vòng đời phương án lịch (module lịch cài đặt bằng một bean {@code @Component}).
 *
 * <p>Quyết định đã chốt (29/10/2026): chỉ lưu trữ kỳ khi kỳ không còn phương án ở trạng thái
 * {@code draft}, {@code pending_approval} hoặc {@code approved}. Bean là bắt buộc với {@code PeriodService}
 * (baseline mục 7): không có chế độ vắng bean để cho lưu trữ. Lưu trữ khóa dòng kỳ trước khi gọi gate; module lịch
 * cũng khóa dòng kỳ trước khi tạo/công bố phương án, nên hai thao tác không chen nhau.
 */
public interface SchedulePlanGate {

    /**
     * Mô tả các phương án chưa hoàn tất của kỳ (ví dụ "plan_x — pending_approval").
     * Rỗng nghĩa là được phép lưu trữ.
     */
    List<String> unfinishedPlans(String periodId);
}
