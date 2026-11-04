package org.dacnpm.schedulemanager.api.rules;

import java.util.Optional;

/**
 * Phạm vi áp dụng quy tắc. Khi nhiều phạm vi cùng có giá trị, phạm vi cụ thể hơn thắng:
 * user &gt; group &gt; employee_type &gt; branch &gt; company (đề xuất của TU-06, cần cả đội xác nhận).
 *
 * <p>branch/employee_type/group chưa có danh mục (A-04 của Anh) nên chưa nhận cấu hình;
 * schema và bộ giải quy tắc đã hỗ trợ sẵn để khi có danh mục chỉ cần bật.
 */
public enum RuleScopeType {
    COMPANY("company", 1, true),
    BRANCH("branch", 2, true),
    EMPLOYEE_TYPE("employee_type", 3, true),
    GROUP("group", 4, true),
    USER("user", 5, true);

    private final String dbValue;
    private final int precedence;
    private final boolean available;

    RuleScopeType(String dbValue, int precedence, boolean available) {
        this.dbValue = dbValue;
        this.precedence = precedence;
        this.available = available;
    }

    public String dbValue() { return dbValue; }

    /** Số lớn hơn = cụ thể hơn = thắng khi trùng. */
    public int precedence() { return precedence; }

    /** Đã có danh mục để cấu hình hay chưa. */
    public boolean available() { return available; }

    public static Optional<RuleScopeType> fromDb(String value) {
        for (RuleScopeType t : values()) {
            if (t.dbValue.equals(value)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
