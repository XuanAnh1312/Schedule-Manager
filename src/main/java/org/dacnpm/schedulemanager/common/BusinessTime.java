package org.dacnpm.schedulemanager.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Quy ước thời gian dùng chung: DB lưu thời điểm theo UTC ({@code SYSUTCDATETIME()} như V5),
 * còn người dùng nhập và đọc theo múi giờ nghiệp vụ của {@link Clock} (Asia/Ho_Chi_Minh,
 * xem {@link TimeConfig}). Không dùng múi giờ máy người dùng để quyết định hết hạn.
 */
public final class BusinessTime {

    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private BusinessTime() {}

    /** Giờ địa phương nghiệp vụ → giờ UTC để lưu DB. */
    public static LocalDateTime toUtc(LocalDateTime local, ZoneId zone) {
        return local == null ? null : local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Giờ UTC đọc từ DB → giờ địa phương nghiệp vụ để trả cho client. */
    public static LocalDateTime toLocal(LocalDateTime utc, ZoneId zone) {
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime();
    }

    public static Instant instantOfUtc(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    public static LocalDateTime nowUtc(Clock clock) {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** Ngày nghiệp vụ hôm nay theo múi giờ của Clock. */
    public static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    /** Nhãn hiển thị, ví dụ "05/10/2026 23:59". */
    public static String label(LocalDateTime utc, ZoneId zone) {
        return utc == null ? null : toLocal(utc, zone).format(LABEL);
    }
}
