package org.dacnpm.schedulemanager.schedule.shift;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cấu hình một ca do admin gửi lên (tạo, sửa, ca khẩn cấp). Giờ kết thúc nhỏ hơn giờ bắt đầu là ca qua đêm.
 * Các tập hợp được sao chép nhưng KHÔNG loại phần tử null, để {@link ShiftConfigRules} báo lỗi rõ ràng
 * thay vì gặp NullPointerException.
 *
 * @param breakMinutes       giờ nghỉ trong ca (phút); null được hiểu là 0
 * @param mode               level, user hoặc combined
 * @param minRequired        số người tối thiểu; null nghĩa là bằng totalRequired
 * @param maxAllowed         số người tối đa; null nghĩa là bằng totalRequired
 * @param levelRequirements  levelId -> số người
 * @param positionIds        nhân viên phải giữ ít nhất một trong các chức vụ này
 */
public record ShiftRequest(
        String name,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        Integer breakMinutes,
        String locationId,
        String mode,
        Integer totalRequired,
        Integer minRequired,
        Integer maxAllowed,
        String note,
        Map<String, Integer> levelRequirements,
        Set<String> mandatoryUserIds,
        Set<String> preferredUserIds,
        Set<String> skillIds,
        Set<String> certificateIds,
        Set<String> positionIds) {

    public ShiftRequest {
        breakMinutes = breakMinutes == null ? 0 : breakMinutes;
        levelRequirements = levelRequirements == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(levelRequirements));
        mandatoryUserIds = freeze(mandatoryUserIds);
        preferredUserIds = freeze(preferredUserIds);
        skillIds = freeze(skillIds);
        certificateIds = freeze(certificateIds);
        positionIds = freeze(positionIds);
    }

    private static Set<String> freeze(Set<String> values) {
        return values == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    /** Bản sao với ngày khác, dùng cho sao chép và lặp ca. */
    public ShiftRequest withDate(LocalDate newDate) {
        return new ShiftRequest(name, newDate, startTime, endTime, breakMinutes, locationId, mode, totalRequired,
                minRequired, maxAllowed, note, levelRequirements, mandatoryUserIds, preferredUserIds, skillIds,
                certificateIds, positionIds);
    }
}