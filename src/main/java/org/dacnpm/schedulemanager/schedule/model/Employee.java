package org.dacnpm.schedulemanager.schedule.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Nhân viên với các thông số dùng cho kiểm tra phân công.
 *
 * @param levelId                   null nếu chưa có Level
 * @param targetRate                tỷ lệ giờ mục tiêu theo % (cột users.target_rate); null là thiếu thông số
 * @param contractMaxHoursPerWeek   giới hạn giờ/tuần theo hợp đồng; null nếu không có
 * @param locationIds               rỗng nghĩa là không giới hạn địa điểm
 * @param certificateValidUntil     mã chứng chỉ -> ngày hết hạn (null nghĩa là không hết hạn)
 * @param positionIds               chức vụ nhân viên đang giữ
 */
public record Employee(
        String id,
        String fullName,
        boolean active,
        String levelId,
        Double targetRate,
        Double contractMaxHoursPerWeek,
        Set<String> locationIds,
        Set<String> skills,
        Map<String, LocalDate> certificateValidUntil,
        Set<String> positionIds) {

    /** Giữ tương thích với nơi gọi cũ chưa có chức vụ. */
    public Employee(String id, String fullName, boolean active, String levelId, Double targetRate,
                    Double contractMaxHoursPerWeek, Set<String> locationIds, Set<String> skills,
                    Map<String, LocalDate> certificateValidUntil) {
        this(id, fullName, active, levelId, targetRate, contractMaxHoursPerWeek, locationIds, skills,
                certificateValidUntil, null);
    }

    public Employee {
        locationIds = locationIds == null ? Set.of() : Set.copyOf(locationIds);
        skills = skills == null ? Set.of() : Set.copyOf(skills);
        // Map.copyOf không cho giá trị null, trong khi null nghĩa là chứng chỉ không hết hạn.
        certificateValidUntil = certificateValidUntil == null
                ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(certificateValidUntil));
        positionIds = positionIds == null ? Set.of() : Set.copyOf(positionIds);
    }

    public String label() {
        return fullName == null || fullName.isBlank() ? id : fullName;
    }
}