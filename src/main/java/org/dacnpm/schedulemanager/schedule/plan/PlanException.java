package org.dacnpm.schedulemanager.schedule.plan;

/** Lỗi nghiệp vụ của phương án lịch, kèm mã lỗi và HTTP status để controller trả về. */
public class PlanException extends RuntimeException {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String PLAN_NOT_EDITABLE = "PLAN_NOT_EDITABLE";
    public static final String PERIOD_NOT_READY = "PERIOD_NOT_READY";

    private final String code;
    private final int status;

    public PlanException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    static PlanException notFound(String message) {
        return new PlanException(NOT_FOUND, 404, message);
    }

    static PlanException conflict(String message) {
        return new PlanException(VERSION_CONFLICT, 409, message);
    }

    static PlanException notEditable(String message) {
        return new PlanException(PLAN_NOT_EDITABLE, 409, message);
    }

    static PlanException periodNotReady(String message) {
        return new PlanException(PERIOD_NOT_READY, 409, message);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
