package org.dacnpm.schedulemanager.domain.schedule;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleService;
import org.dacnpm.schedulemanager.schedule.plan.PlanException;
import org.dacnpm.schedulemanager.schedule.shift.ShiftNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read contract retained; every mutation and validation uses the scheduling core.
 *
 * <p>Lỗi của lõi lịch ({@link PlanException}, ca không tồn tại, dữ liệu không hợp lệ) được chuyển thành
 * {@link ApiException} đúng mã HTTP theo hợp đồng {@link AssignmentMutationService}, để controller của module đổi ca,
 * nghỉ phép và ca trống trả 404/409/422 thay vì 500.
 *
 * <p>Thông báo (BA-R1): module gọi đã phát sự kiện nghiệp vụ (shift_request.approved, leave.approved,
 * open_shift.claim_approved) cho đúng người nhận, nên lõi lịch được gọi với {@code notify = false}: mỗi thao tác chỉ tạo
 * một thông báo. Lịch sử, version và kiểm tra ràng buộc vẫn do lõi lịch thực hiện.
 */
@Component
@Profile("sqlserver")
public class OfficialJdbcScheduleAdapter extends InterimJdbcScheduleAdapter {
    private final OfficialScheduleService schedule;
    public OfficialJdbcScheduleAdapter(JdbcTemplate jdbc, OfficialScheduleService schedule) {
        super(jdbc);
        this.schedule = schedule;
    }
    @Override
    public List<Violation> validate(String userId, String shiftId, String replacingUserId,
                                    Set<String> ignoreAssignmentIds, String requiredLevelId) {
        return translated(() -> schedule.validate(userId, shiftId, replacingUserId, ignoreAssignmentIds, requiredLevelId)
                .violations().stream().map(v -> Violation.block(v.code().name(), v.message())).toList());
    }
    @Override
    public void reassign(String id, String expected, String next, String actor, String reason) {
        run(() -> schedule.reassign(id, expected, next, actor, reason, null, false));
    }
    @Override
    public String addAssignment(String shiftId, String userId, String actor, String reason) {
        return translated(() -> schedule.addAssignment(shiftId, userId, actor, reason, null, false).assignmentId());
    }
    @Override
    public void cancelAssignment(String id, String expected, String actor, String reason) {
        run(() -> schedule.cancelAssignment(id, expected, actor, reason, false));
    }
    @Override
    public void swap(String firstAssignmentId, String firstUserId, String secondAssignmentId, String secondUserId,
                     String actorId, String reason) {
        run(() -> schedule.swap(firstAssignmentId, firstUserId, secondAssignmentId, secondUserId, actorId, reason, false));
    }

    static <T> T translated(Supplier<T> call) {
        try {
            return call.get();
        } catch (PlanException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new ApiException(status == null ? HttpStatus.CONFLICT : status, e.code(), e.getMessage(), e.details());
        } catch (ShiftNotFoundException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", e.getMessage());
        }
    }

    private static void run(Runnable call) {
        translated(() -> {
            call.run();
            return null;
        });
    }
}
