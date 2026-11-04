package org.dacnpm.schedulemanager.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Đồng hồ giả lập cho test hạn chót: đặt hoặc tua thời gian. */
public class MutableClock extends Clock {

    public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /** Đồng hồ ở thời điểm theo giờ Việt Nam. */
    public static MutableClock atVn(LocalDateTime local) {
        return new MutableClock(local.atZone(VN).toInstant(), VN);
    }

    public void setVn(LocalDateTime local) {
        this.now = local.atZone(zone).toInstant();
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
