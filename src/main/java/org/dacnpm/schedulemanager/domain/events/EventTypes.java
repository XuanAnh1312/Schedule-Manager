package org.dacnpm.schedulemanager.domain.events;

/** Danh mục loại sự kiện. Module khác thêm hằng số mới ở đây để thống nhất tên. */
public final class EventTypes {

    private EventTypes() {}

    // Đổi ca / làm thay (Bảo)
    public static final String SHIFT_REQUEST_CREATED = "shift_request.created";
    public static final String SHIFT_REQUEST_PEER_ACCEPTED = "shift_request.peer_accepted";
    public static final String SHIFT_REQUEST_PEER_DECLINED = "shift_request.peer_declined";
    public static final String SHIFT_REQUEST_APPROVED = "shift_request.approved";
    public static final String SHIFT_REQUEST_REJECTED = "shift_request.rejected";
    public static final String SHIFT_REQUEST_CANCELLED = "shift_request.cancelled";

    // Nghỉ phép (Bảo)
    public static final String LEAVE_REQUESTED = "leave.requested";
    public static final String LEAVE_APPROVED = "leave.approved";
    public static final String LEAVE_REJECTED = "leave.rejected";
    public static final String LEAVE_CANCELLED = "leave.cancelled";
    public static final String COVERAGE_WARNING = "schedule.coverage_warning";
    public static final String ABSENCE_RECORDED = "absence.recorded";

    // Ca trống (Bảo)
    public static final String OPEN_SHIFT_PUBLISHED = "open_shift.published";
    public static final String OPEN_SHIFT_CLAIMED = "open_shift.claimed";
    public static final String OPEN_SHIFT_CLAIM_APPROVED = "open_shift.claim_approved";
    public static final String OPEN_SHIFT_CLAIM_REJECTED = "open_shift.claim_rejected";

    // Đăng ký kỳ (job nhắc hạn của Bảo, dữ liệu kỳ của Tuấn)
    public static final String PERIOD_DEADLINE_REMINDER = "period.deadline_reminder";

    // Lịch (Tường phát sau commit công bố/sửa phân công)
    public static final String SCHEDULE_PUBLISHED = "schedule.published";
    public static final String SCHEDULE_CHANGED = "schedule.changed";
    public static final String ASSIGNMENT_ADDED = "assignment.added";
}
