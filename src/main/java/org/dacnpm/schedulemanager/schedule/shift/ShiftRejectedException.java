package org.dacnpm.schedulemanager.schedule.shift;

import java.util.List;
import org.dacnpm.schedulemanager.schedule.validation.Violation;

/**
 * Ca bị từ chối: lỗi cấu hình (errors) và/hoặc nhân viên chỉ định không qua kiểm tra cứng (violations).
 * warnings là các vi phạm có thể xem xét ngoại lệ (không chặn).
 */
public class ShiftRejectedException extends RuntimeException {

    private final List<String> errors;
    private final List<Violation> violations;
    private final List<Violation> warnings;

    public ShiftRejectedException(List<String> errors, List<Violation> violations, List<Violation> warnings) {
        super(buildMessage(errors, violations));
        this.errors = List.copyOf(errors);
        this.violations = List.copyOf(violations);
        this.warnings = List.copyOf(warnings);
    }

    private static String buildMessage(List<String> errors, List<Violation> violations) {
        List<String> parts = new java.util.ArrayList<>(errors);
        violations.forEach(v -> parts.add(v.message()));
        return String.join(" ", parts);
    }

    public List<String> errors() {
        return errors;
    }

    public List<Violation> violations() {
        return violations;
    }

    public List<Violation> warnings() {
        return warnings;
    }
}
