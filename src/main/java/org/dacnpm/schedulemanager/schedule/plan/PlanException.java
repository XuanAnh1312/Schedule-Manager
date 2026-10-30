package org.dacnpm.schedulemanager.schedule.plan;

import java.util.List;

/**
 * Lỗi nghiệp vụ của phương án và lịch chính thức, kèm mã lỗi, HTTP status và chi tiết (ví dụ từng vi phạm) để
 * controller trả về.
 */
public class PlanException extends RuntimeException {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String PLAN_NOT_EDITABLE = "PLAN_NOT_EDITABLE";
    public static final String PERIOD_NOT_READY = "PERIOD_NOT_READY";
    /** Có vi phạm ràng buộc bắt buộc: không được duyệt/công bố/ghi, kể cả bằng ngoại lệ. */
    public static final String HARD_VIOLATIONS = "HARD_VIOLATIONS";
    /** Vi phạm chỉ được phép khi có ngoại lệ đã duyệt. */
    public static final String EXCEPTION_REQUIRED = "EXCEPTION_REQUIRED";
    public static final String CONFIRM_UNDERSTAFFED = "CONFIRM_UNDERSTAFFED";
    public static final String UNDERSTAFFED_BLOCKED = "UNDERSTAFFED_BLOCKED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String STALE_PREVIEW = "STALE_PREVIEW";
    public static final String CONFIRM_REQUIRED = "CONFIRM_REQUIRED";

    private final String code;
    private final int status;
    private final List<String> details;

    public PlanException(String code, int status, String message) {
        this(code, status, message, List.of());
    }

    public PlanException(String code, int status, String message, List<String> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static PlanException notFound(String message) {
        return new PlanException(NOT_FOUND, 404, message);
    }

    public static PlanException conflict(String message) {
        return new PlanException(VERSION_CONFLICT, 409, message);
    }

    public static PlanException notEditable(String message) {
        return new PlanException(PLAN_NOT_EDITABLE, 409, message);
    }

    public static PlanException periodNotReady(String message) {
        return new PlanException(PERIOD_NOT_READY, 409, message);
    }

    public static PlanException of(String code, int status, String message, List<String> details) {
        return new PlanException(code, status, message, details);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public List<String> details() {
        return details;
    }
}
