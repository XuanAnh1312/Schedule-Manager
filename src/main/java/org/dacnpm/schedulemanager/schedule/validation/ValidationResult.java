package org.dacnpm.schedulemanager.schedule.validation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Kết quả kiểm tra một phân công. */
public record ValidationResult(List<Violation> violations) {

    public ValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    @JsonProperty("valid")
    public boolean valid() {
        return violations.isEmpty();
    }

    /** Có vi phạm và mọi vi phạm đều thuộc nhóm có thể xin ngoại lệ. */
    @JsonProperty("overridable")
    public boolean overridable() {
        return !violations.isEmpty() && violations.stream().allMatch(v -> v.code().overridable());
    }

    public boolean has(ViolationCode code) {
        return violations.stream().anyMatch(v -> v.code() == code);
    }
}
