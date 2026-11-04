package org.dacnpm.schedulemanager.schedule.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.model.Assignment;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleRepository;

/** Lịch chính thức trong bộ nhớ, cùng điều kiện ghi (người giữ + version + trạng thái) như bản SQL. */
public class FakeOfficialSchedule extends OfficialScheduleRepository {

    public final Map<String, OfficialAssignment> rows = new LinkedHashMap<>();
    public final Set<String> publishedShifts = new HashSet<>();
    private int sequence;

    public FakeOfficialSchedule() {
        super(null);
    }

    /** Phân công confirmed dưới dạng dùng cho ScheduleData. */
    public List<Assignment> asAssignments() {
        return rows.values().stream().map(a -> new Assignment(a.id(), a.shiftId(), a.userId(), a.status())).toList();
    }

    public List<OfficialAssignment> confirmed() {
        return rows.values().stream().filter(OfficialAssignment::confirmed).toList();
    }

    public String put(String shiftId, String userId) {
        return addOrReactivate(shiftId, userId, "seed", null, null);
    }

    @Override
    public Optional<OfficialAssignment> find(String assignmentId) {
        return Optional.ofNullable(rows.get(assignmentId));
    }

    @Override
    public List<OfficialAssignment> confirmedByShifts(Collection<String> shiftIds) {
        return confirmed().stream().filter(a -> shiftIds.contains(a.shiftId())).toList();
    }

    @Override
    public List<OfficialAssignment> confirmedOfUser(String userId, LocalDate from, LocalDate to) {
        return confirmed().stream().filter(a -> a.userId().equals(userId)).toList();
    }

    @Override
    public String addOrReactivate(String shiftId, String userId, String actorId, String reason, String sourcePlanId) {
        for (OfficialAssignment a : new ArrayList<>(rows.values())) {
            if (a.shiftId().equals(shiftId) && a.userId().equals(userId)) {
                if (a.confirmed()) {
                    throw new org.dacnpm.schedulemanager.schedule.plan.PlanException(
                            org.dacnpm.schedulemanager.schedule.plan.PlanException.ALREADY_ASSIGNED, 409, "Đã được phân.");
                }
                rows.put(a.id(), copy(a, "confirmed", a.version() + 1));
                return a.id();
            }
        }
        String id = "asgn_" + (++sequence);
        rows.put(id, new OfficialAssignment(id, shiftId, userId, "confirmed", 1, null, null, null, null, null));
        return id;
    }

    @Override
    public boolean cancel(String assignmentId, String expectedUserId, int expectedVersion, String reason) {
        OfficialAssignment a = rows.get(assignmentId);
        if (a == null || !a.confirmed() || !a.userId().equals(expectedUserId) || a.version() != expectedVersion) {
            return false;
        }
        rows.put(a.id(), copy(a, "cancelled", a.version() + 1));
        return true;
    }

    @Override
    public void markShiftsPublished(Collection<String> shiftIds) {
        publishedShifts.addAll(shiftIds);
    }

    private static OfficialAssignment copy(OfficialAssignment a, String status, int version) {
        return new OfficialAssignment(a.id(), a.shiftId(), a.userId(), status, version, a.shiftName(), a.shiftDate(),
                a.startTime(), a.endTime(), a.shiftStatus());
    }
}
