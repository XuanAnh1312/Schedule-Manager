package org.dacnpm.schedulemanager.schedule.shift;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.ShiftInfo;

/**
 * Kiểm tra cấu hình ca (không cần dữ liệu ngoài). Kế thừa quy tắc của Validate.shiftForm /
 * shiftStaffConfig trong validation.js và bổ sung giờ nghỉ, số tối thiểu/tối đa.
 */
public final class ShiftConfigRules {

    private static final Set<String> MODES = Set.of("level", "user", "combined");

    private ShiftConfigRules() {
    }

    public static List<String> check(ShiftRequest r) {
        List<String> errors = new ArrayList<>();
        if (r.name() == null || r.name().isBlank()) {
            errors.add("Tên ca không được để trống.");
        } else if (r.name().trim().length() > 120) {
            errors.add("Tên ca không được vượt quá 120 ký tự.");
        }
        if (r.note() != null && r.note().length() > 1000) {
            errors.add("Ghi chú không được vượt quá 1000 ký tự.");
        }
        if (r.date() == null) {
            errors.add("Ngày làm không được để trống.");
        }
        if (r.startTime() == null) {
            errors.add("Giờ bắt đầu không được để trống.");
        }
        if (r.endTime() == null) {
            errors.add("Giờ kết thúc không được để trống.");
        }
        if (r.startTime() != null && r.endTime() != null) {
            if (r.startTime().equals(r.endTime())) {
                errors.add("Giờ bắt đầu không được trùng giờ kết thúc.");
            } else {
                long duration = Duration.between(r.startTime(), r.endTime()).toMinutes();
                if (duration < 0) {
                    duration += 1440; // ca qua đêm
                }
                if (r.breakMinutes() >= duration) {
                    errors.add("Giờ nghỉ (" + r.breakMinutes() + " phút) phải nhỏ hơn thời lượng ca (" + duration + " phút).");
                }
            }
        }
        if (r.breakMinutes() < 0) {
            errors.add("Giờ nghỉ không được âm.");
        }

        if (r.mode() == null || !MODES.contains(r.mode())) {
            errors.add("Chế độ cấu hình nhân sự phải là level, user hoặc combined.");
        }
        if (r.totalRequired() == null || r.totalRequired() <= 0) {
            errors.add("Tổng nhu cầu nhân sự phải là số nguyên dương.");
        } else {
            if (r.minRequired() != null && (r.minRequired() <= 0 || r.minRequired() > r.totalRequired())) {
                errors.add("Số người tối thiểu phải lớn hơn 0 và không vượt tổng nhu cầu (" + r.totalRequired() + ").");
            }
            if (r.maxAllowed() != null && r.maxAllowed() < r.totalRequired()) {
                errors.add("Số người tối đa không được nhỏ hơn tổng nhu cầu (" + r.totalRequired() + ").");
            }
        }

        // Phần tử rỗng/null trong các danh sách tham chiếu phải được báo lỗi trước khi các bước sau xử lý chúng.
        if (!checkNoEmptyReferences(r, errors)) {
            return errors;
        }
        if (r.mode() != null && MODES.contains(r.mode()) && r.totalRequired() != null && r.totalRequired() > 0) {
            checkStaffing(r, errors);
        }
        return errors;
    }

    /** Trả về false nếu có Level, số lượng hoặc mã tham chiếu rỗng/null. */
    private static boolean checkNoEmptyReferences(ShiftRequest r, List<String> errors) {
        boolean ok = true;
        if (r.levelRequirements().keySet().stream().anyMatch(ShiftConfigRules::blank)) {
            errors.add("Mỗi dòng Level phải chọn Level.");
            ok = false;
        }
        if (r.levelRequirements().values().stream().anyMatch(count -> count == null || count <= 0)) {
            errors.add("Số lượng mỗi Level phải > 0.");
            ok = false;
        }
        ok &= noBlank(r.mandatoryUserIds(), "Danh sách nhân viên bắt buộc", errors);
        ok &= noBlank(r.preferredUserIds(), "Danh sách nhân viên ưu tiên", errors);
        ok &= noBlank(r.skillIds(), "Danh sách kỹ năng", errors);
        ok &= noBlank(r.certificateIds(), "Danh sách chứng chỉ", errors);
        ok &= noBlank(r.positionIds(), "Danh sách chức vụ", errors);
        return ok;
    }

    private static boolean noBlank(Set<String> ids, String label, List<String> errors) {
        if (ids.stream().anyMatch(ShiftConfigRules::blank)) {
            errors.add(label + " không được chứa giá trị rỗng.");
            return false;
        }
        return true;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void checkStaffing(ShiftRequest r, List<String> errors) {
        int total = r.totalRequired();
        int levelTotal = 0;
        for (var entry : r.levelRequirements().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                errors.add("Mỗi dòng Level phải chọn Level.");
            }
            if (entry.getValue() == null || entry.getValue() <= 0) {
                errors.add("Số lượng mỗi Level phải > 0.");
            } else {
                levelTotal += entry.getValue();
            }
        }
        Set<String> both = new HashSet<>(r.mandatoryUserIds());
        both.retainAll(r.preferredUserIds());
        if (!both.isEmpty()) {
            errors.add("Nhân viên không thể vừa bắt buộc vừa ưu tiên: " + String.join(", ", new java.util.TreeSet<>(both)) + ".");
        }

        switch (r.mode()) {
            case "level" -> {
                if (r.levelRequirements().isEmpty()) {
                    errors.add("Phải có ít nhất một yêu cầu Level.");
                } else if (levelTotal != total) {
                    errors.add("Tổng Level (" + levelTotal + ") phải bằng tổng nhu cầu (" + total + ").");
                }
            }
            case "user" -> {
                if (r.mandatoryUserIds().isEmpty() && r.preferredUserIds().isEmpty()) {
                    errors.add("Phải chọn ít nhất một nhân viên.");
                }
                if (r.mandatoryUserIds().size() > total) {
                    errors.add("Số nhân viên bắt buộc (" + r.mandatoryUserIds().size() + ") vượt tổng nhu cầu (" + total + ").");
                }
            }
            default -> {
                if (r.mandatoryUserIds().size() + levelTotal > total) {
                    errors.add("Nhân viên bắt buộc (" + r.mandatoryUserIds().size() + ") + vị trí Level (" + levelTotal
                            + ") vượt tổng nhu cầu (" + total + ").");
                }
            }
        }
    }

    /** Dựng ShiftInfo để đưa qua AssignmentValidator. Chỉ gọi sau khi {@link #check} không có lỗi. */
    public static ShiftInfo toShiftInfo(String id, ShiftRequest r) {
        return new ShiftInfo(id, r.name().trim(), r.date(), r.startTime(), r.endTime(), r.breakMinutes(), r.mode(),
                r.totalRequired(), r.maxAllowed(), r.levelRequirements(), r.mandatoryUserIds(), r.preferredUserIds(),
                r.skillIds(), r.certificateIds(), r.locationId(), r.positionIds(), r.minRequired());
    }
}
