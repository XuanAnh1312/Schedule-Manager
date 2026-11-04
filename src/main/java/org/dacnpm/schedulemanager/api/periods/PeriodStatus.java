package org.dacnpm.schedulemanager.api.periods;

/**
 * Trạng thái lưu trong {@code periods.status} (V7). Chỉ là vòng đời đăng ký; trạng thái
 * xếp lịch/công bố thuộc phương án lịch, không lưu ở đây.
 */
public enum PeriodStatus {
    DRAFT("draft"),
    OPEN("open"),
    LOCKED("locked"),
    ARCHIVED("archived");

    private final String dbValue;

    PeriodStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static PeriodStatus fromDb(String value) {
        for (PeriodStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("Unknown period status in database: " + value);
    }
}
