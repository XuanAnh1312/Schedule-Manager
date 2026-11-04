package org.dacnpm.schedulemanager.schedule.shift;

public class ShiftVersionConflictException extends RuntimeException {
    public ShiftVersionConflictException(String message) {
        super(message);
    }
}
