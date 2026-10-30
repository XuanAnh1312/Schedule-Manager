package org.dacnpm.schedulemanager.schedule.shift;

public class ShiftNotFoundException extends RuntimeException {
    public ShiftNotFoundException(String message) {
        super(message);
    }
}
