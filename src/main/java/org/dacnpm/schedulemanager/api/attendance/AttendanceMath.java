package org.dacnpm.schedulemanager.api.attendance;

import java.time.*;

/** Testable business arithmetic; negative durations are not silently accepted. */
public final class AttendanceMath {
    private AttendanceMath(){}
    public static long workedMinutes(LocalDateTime start,LocalDateTime end,int breakMinutes) {
        long elapsed=Duration.between(start,end).toMinutes();
        if(elapsed<=0||breakMinutes<0||breakMinutes>=elapsed)
            throw new IllegalArgumentException("Invalid work time/break");
        return elapsed-breakMinutes;
    }
    public static long lateMinutes(LocalDateTime in,LocalDateTime scheduledStart,int graceMinutes) {
        return Math.max(0,Duration.between(scheduledStart.plusMinutes(graceMinutes),in).toMinutes());
    }
    public static long overtimeMinutes(LocalDateTime in,LocalDateTime out,int breakMinutes,
                                       LocalDateTime scheduledStart,LocalDateTime scheduledEnd) {
        return Math.max(0,workedMinutes(in,out,breakMinutes)-Duration.between(scheduledStart,scheduledEnd).toMinutes());
    }
    /** Night shift window 22:00–06:00 in policy timezone. Returned minutes BEFORE any unspecified breaks. */
    public static long nightMinutes(LocalDateTime inUtc,LocalDateTime outUtc,ZoneId zone) {
        Instant start=inUtc.toInstant(ZoneOffset.UTC),end=outUtc.toInstant(ZoneOffset.UTC);
        if(!end.isAfter(start))return 0;
        LocalDate first=start.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last=end.atZone(zone).toLocalDate();
        long total=0;
        for(LocalDate d=first;!d.isAfter(last);d=d.plusDays(1)){
            Instant nightStart=d.atTime(22,0).atZone(zone).toInstant();
            Instant nightEnd=d.plusDays(1).atTime(6,0).atZone(zone).toInstant();
            Instant lo=start.isAfter(nightStart)?start:nightStart;
            Instant hi=end.isBefore(nightEnd)?end:nightEnd;
            if(hi.isAfter(lo))total+=Duration.between(lo,hi).toMinutes();
        }
        return total;
    }
}
