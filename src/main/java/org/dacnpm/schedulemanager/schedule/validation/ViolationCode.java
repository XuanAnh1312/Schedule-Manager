package org.dacnpm.schedulemanager.schedule.validation;

/**
 * Mã vi phạm. {@code overridable} chỉ đánh dấu những ràng buộc mà quy trình ngoại lệ (TW-06)
 * có thể xem xét; mọi ràng buộc còn lại là bắt buộc và không bao giờ được override.
 */
public enum ViolationCode {
    UNKNOWN_EMPLOYEE(false),
    INACTIVE(false),
    ALREADY_ASSIGNED(false),
    SHIFT_FULL(false),
    OVERLAP(false),
    MIN_REST(false),
    HOURS_PER_DAY(false),
    HOURS_PER_WEEK(false),
    HOURS_PER_PERIOD(false),
    CONSECUTIVE_DAYS(false),
    LEVEL(false),
    POSITION(false),
    SKILL(false),
    CERTIFICATE(false),
    LOCATION(false),
    MISSING_PARAMETER(false),
    SHIFT_CANCELLED(false),
    UNAVAILABLE(true),
    NOT_AVAILABLE_TIME(true);

    private final boolean overridable;

    ViolationCode(boolean overridable) {
        this.overridable = overridable;
    }

    public boolean overridable() {
        return overridable;
    }
}
