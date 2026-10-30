package org.dacnpm.schedulemanager.schedule.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionRepository;

/** Quyền riêng và ngoại lệ trong bộ nhớ. */
public class FakeExceptions extends ExceptionRepository {

    public record Record(String id, String planId, String shiftId, String userId, Set<String> codes, String reason,
                         String approvedBy) {
    }

    public final Set<String> permitted = new HashSet<>();
    public final List<Record> records = new ArrayList<>();

    public FakeExceptions() {
        super(null);
    }

    @Override
    public boolean hasPermission(String userId, String permission) {
        return permitted.contains(userId);
    }

    @Override
    public void insert(String id, String planId, String shiftId, String userId, Set<String> codes,
                       String violationsJson, String reason, String approvedBy) {
        records.add(new Record(id, planId, shiftId, userId, Set.copyOf(codes), reason, approvedBy));
    }

    @Override
    public Set<String> activeCodes(String planId, String shiftId, String userId) {
        Set<String> codes = new HashSet<>();
        records.stream()
                .filter(r -> Objects.equals(r.planId(), planId) && r.shiftId().equals(shiftId) && r.userId().equals(userId))
                .forEach(r -> codes.addAll(r.codes()));
        return codes;
    }
}
