package org.dacnpm.schedulemanager.schedule.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ca làm việc dùng cho kiểm tra và phân lịch.
 * Giờ kết thúc nhỏ hơn giờ bắt đầu nghĩa là ca qua đêm (kết thúc ngày hôm sau).
 *
 * @param mode               level, user hoặc combined (khớp cột assignment_mode)
 * @param breakMinutes       giờ nghỉ trong ca; bị trừ khỏi thời gian làm việc tính vào giới hạn giờ
 * @param maxAllowed         số người tối đa; null nghĩa là bằng totalRequired
 * @param levelRequirements  levelId -> số người
 * @param locationId         null nếu ca không gắn địa điểm
 * @param requiredPositionIds nhân viên phải giữ ít nhất một trong các chức vụ này; rỗng là không yêu cầu
 */
public record ShiftInfo(
        String id,
        String name,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        int breakMinutes,
        String mode,
        int totalRequired,
        Integer maxAllowed,
        Map<String, Integer> levelRequirements,
        Set<String> mandatoryUserIds,
        Set<String> preferredUserIds,
        Set<String> requiredSkills,
        Set<String> requiredCertificates,
        String locationId,
        Set<String> requiredPositionIds) {

    /** Giữ tương thích với nơi gọi cũ chưa có chức vụ yêu cầu. */
    public ShiftInfo(String id, String name, LocalDate date, LocalTime startTime, LocalTime endTime,
                     int breakMinutes, String mode, int totalRequired, Integer maxAllowed,
                     Map<String, Integer> levelRequirements, Set<String> mandatoryUserIds,
                     Set<String> preferredUserIds, Set<String> requiredSkills,
                     Set<String> requiredCertificates, String locationId) {
        this(id, name, date, startTime, endTime, breakMinutes, mode, totalRequired, maxAllowed,
                levelRequirements, mandatoryUserIds, preferredUserIds, requiredSkills, requiredCertificates,
                locationId, null);
    }

    public ShiftInfo {
        if (startTime.equals(endTime)) {
            throw new IllegalArgumentException("Ca " + id + " có giờ bắt đầu trùng giờ kết thúc.");
        }
        if (breakMinutes < 0) {
            throw new IllegalArgumentException("Ca " + id + " có giờ nghỉ âm.");
        }
        levelRequirements = levelRequirements == null ? Map.of() : Map.copyOf(levelRequirements);
        mandatoryUserIds = mandatoryUserIds == null ? Set.of() : Set.copyOf(mandatoryUserIds);
        preferredUserIds = preferredUserIds == null ? Set.of() : Set.copyOf(preferredUserIds);
        requiredSkills = requiredSkills == null ? Set.of() : Set.copyOf(requiredSkills);
        requiredCertificates = requiredCertificates == null ? Set.of() : Set.copyOf(requiredCertificates);
        requiredPositionIds = requiredPositionIds == null ? Set.of() : Set.copyOf(requiredPositionIds);
        if (breakMinutes >= Duration.between(
                date.atTime(startTime),
                endTime.isAfter(startTime) ? date.atTime(endTime) : date.plusDays(1).atTime(endTime)).toMinutes()) {
            throw new IllegalArgumentException("Ca " + id + " có giờ nghỉ không nhỏ hơn thời lượng ca.");
        }
    }

    public boolean overnight() {
        return endTime.isBefore(startTime);
    }

    public LocalDateTime startAt() {
        return date.atTime(startTime);
    }

    public LocalDateTime endAt() {
        return (overnight() ? date.plusDays(1) : date).atTime(endTime);
    }

    public int capacity() {
        return maxAllowed == null ? totalRequired : maxAllowed;
    }

    public List<String> levelIds() {
        return levelRequirements.keySet().stream().sorted().toList();
    }
}
