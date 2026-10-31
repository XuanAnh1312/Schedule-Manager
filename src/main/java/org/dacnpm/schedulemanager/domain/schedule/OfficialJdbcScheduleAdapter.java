package org.dacnpm.schedulemanager.domain.schedule;

import java.util.List;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read contract retained; every mutation and validation uses the scheduling core. */
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
        return schedule.validate(userId, shiftId, replacingUserId, ignoreAssignmentIds, requiredLevelId)
                .violations().stream().map(v -> Violation.block(v.code().name(), v.message())).toList();
    }
    @Override
    public void reassign(String id, String expected, String next, String actor, String reason) {
        schedule.reassign(id, expected, next, actor, reason, null);
    }
    @Override
    public String addAssignment(String shiftId, String userId, String actor, String reason) {
        return schedule.addAssignment(shiftId, userId, actor, reason, null).assignmentId();
    }
    @Override
    public void cancelAssignment(String id, String expected, String actor, String reason) {
        schedule.cancelAssignment(id, expected, actor, reason);
    }
}
