package org.dacnpm.schedulemanager.api.leave;

import java.time.LocalDate;
import java.util.List;

/**
 * Tác động của đơn nghỉ lên lịch: các ca người nghỉ đang được phân và độ phủ còn lại nếu duyệt.
 */
public record LeaveImpact(List<AffectedShift> shifts, int understaffedAfterApproval) {

    public record AffectedShift(String assignmentId, String shiftId, String shiftName, LocalDate date,
                                String time, boolean published, int required, int confirmedNow,
                                int confirmedAfter, boolean understaffedAfter) {}
}
