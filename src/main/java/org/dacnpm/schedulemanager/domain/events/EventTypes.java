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
    public static final String SHIFT_REQUEST_STEP_APPROVED = "shift_request.step_approved";

    // Nghỉ phép (Bảo)
    public static final String LEAVE_REQUESTED = "leave.requested";
    public static final String LEAVE_APPROVED = "leave.approved";
    public static final String LEAVE_REJECTED = "leave.rejected";
    public static final String LEAVE_CANCELLED = "leave.cancelled";
    /** BAO-04: một bước duyệt (không phải bước cuối) đã xong, chờ người duyệt kế tiếp. */
    public static final String LEAVE_STEP_APPROVED = "leave.step_approved";
    public static final String COVERAGE_WARNING = "schedule.coverage_warning";
    public static final String ABSENCE_RECORDED = "absence.recorded";

    // Ca trống (Bảo)
    public static final String OPEN_SHIFT_PUBLISHED = "open_shift.published";
    public static final String OPEN_SHIFT_CLAIMED = "open_shift.claimed";
    public static final String OPEN_SHIFT_CLAIM_APPROVED = "open_shift.claim_approved";
    public static final String OPEN_SHIFT_CLAIM_REJECTED = "open_shift.claim_rejected";

    // Đăng ký kỳ (job nhắc hạn của Bảo, dữ liệu kỳ của Tuấn)
    public static final String PERIOD_DEADLINE_REMINDER = "period.deadline_reminder";

    // Giao tiếp nội bộ + email (Bảo, BAO-05)
    public static final String ANNOUNCEMENT_PUBLISHED = "announcement.published";
    public static final String ATTENDANCE_MISSING_CHECKIN = "attendance.missing_checkin";
    /** Do module LMS (Tuấn) phát khi có khóa học sắp hạn — module thông báo đã định tuyến sẵn kênh email. */
    public static final String LMS_COURSE_REMINDER = "lms.course_reminder";
    /** Do module HR (Anh) phát khi hợp đồng/chứng chỉ sắp hết hạn. */
    public static final String HR_CONTRACT_EXPIRING = "hr.contract_expiring";
    public static final String HR_CERTIFICATE_EXPIRING = "hr.certificate_expiring";

    // Hiệu suất / KPI (Bảo, BAO-06). Đề xuất thưởng → Payroll (Bình) nhận; đề xuất đào tạo → LMS (Tuấn) nhận.
    public static final String REVIEW_OPENED = "review.opened";
    public static final String REVIEW_SELF_SUBMITTED = "review.self_submitted";
    public static final String REVIEW_MANAGER_SUBMITTED = "review.manager_submitted";
    public static final String PERFORMANCE_BONUS_PROPOSED = "performance.bonus_proposed";
    public static final String PERFORMANCE_TRAINING_PROPOSED = "performance.training_proposed";
    public static final String PERFORMANCE_PROMOTION_PROPOSED = "performance.promotion_proposed";
    public static final String PERFORMANCE_COMMENDATION = "performance.commendation";

    // Lịch (Tường phát sau commit công bố/sửa phân công)
    public static final String SCHEDULE_PUBLISHED = "schedule.published";
    public static final String SCHEDULE_CHANGED = "schedule.changed";
    public static final String ASSIGNMENT_ADDED = "assignment.added";
}
