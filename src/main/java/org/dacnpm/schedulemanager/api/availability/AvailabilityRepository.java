package org.dacnpm.schedulemanager.api.availability;

import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Entry;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Slot;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Truy cập {@code availability_registrations/entries/slots} (V5, slot_type từ V7). Thời điểm là UTC. */
@Profile("sqlserver")
@Repository
public class AvailabilityRepository {

    /** Dòng đăng ký (chưa kèm ngày/khoảng giờ). */
    public record RegistrationRow(String id, String userId, String periodId, String status,
                                  LocalDateTime submittedAtUtc, LocalDateTime updatedAtUtc) {}

    /** Khoảng giờ đã gửi, phẳng để tổng hợp. */
    public record SubmittedSlot(String userId, LocalDate date, LocalTime start, LocalTime end, String type) {}

    private final JdbcTemplate jdbc;

    public AvailabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RegistrationRow> find(String userId, String periodId) {
        return jdbc.query("""
                SELECT id, user_id, period_id, status, submitted_at, updated_at
                  FROM availability_registrations WHERE user_id = ? AND period_id = ?
                """, (rs, i) -> new RegistrationRow(rs.getString("id"), rs.getString("user_id"),
                rs.getString("period_id"), rs.getString("status"),
                rs.getObject("submitted_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)), userId, periodId).stream().findFirst();
    }

    public List<RegistrationRow> findByPeriod(String periodId) {
        return jdbc.query("""
                SELECT id, user_id, period_id, status, submitted_at, updated_at
                  FROM availability_registrations
                 WHERE period_id = ?
                 ORDER BY user_id
                """, (rs, i) -> new RegistrationRow(rs.getString("id"), rs.getString("user_id"),
                rs.getString("period_id"), rs.getString("status"),
                rs.getObject("submitted_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)), periodId);
    }

    public void insert(String id, String userId, String periodId, String status, LocalDateTime submittedAtUtc) {
        jdbc.update("""
                INSERT INTO availability_registrations (id, user_id, period_id, status, submitted_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, userId, periodId, status, submittedAtUtc);
    }

    public void updateStatus(String id, String status, LocalDateTime submittedAtUtc) {
        jdbc.update("""
                UPDATE availability_registrations
                   SET status = ?, submitted_at = ?, updated_at = SYSUTCDATETIME()
                 WHERE id = ?
                """, status, submittedAtUtc, id);
    }

    /** Thay toàn bộ ngày/khoảng giờ của đăng ký (slots xóa theo ON DELETE CASCADE). */
    public void replaceEntries(String registrationId, List<Entry> entries) {
        jdbc.update("DELETE FROM availability_entries WHERE registration_id = ?", registrationId);
        for (Entry entry : entries) {
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO availability_entries (registration_id, available_date) VALUES (?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, registrationId);
                ps.setObject(2, entry.date());
                return ps;
            }, key);
            long entryId = key.getKey().longValue();
            for (Slot slot : entry.slots()) {
                jdbc.update("""
                        INSERT INTO availability_slots (entry_id, start_time, end_time, slot_type)
                        VALUES (?, ?, ?, ?)
                        """, entryId, slot.start(), slot.end(), slot.type());
            }
        }
    }

    public List<Entry> loadEntries(String registrationId) {
        Map<LocalDate, List<Slot>> byDate = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.available_date, s.start_time, s.end_time, s.slot_type
                  FROM availability_entries e
                  JOIN availability_slots s ON s.entry_id = e.id
                 WHERE e.registration_id = ?
                 ORDER BY e.available_date, s.start_time
                """, rs -> {
            LocalDate date = rs.getObject("available_date", LocalDate.class);
            byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(new Slot(
                    rs.getObject("start_time", LocalTime.class),
                    rs.getObject("end_time", LocalTime.class),
                    rs.getString("slot_type")));
        }, registrationId);
        List<Entry> entries = new ArrayList<>();
        byDate.forEach((date, slots) -> entries.add(new Entry(date, List.copyOf(slots))));
        return entries;
    }

    public List<SubmittedSlot> findSubmittedSlots(String periodId) {
        return jdbc.query("""
                SELECT r.user_id, e.available_date, s.start_time, s.end_time, s.slot_type
                  FROM availability_registrations r
                  JOIN availability_entries e ON e.registration_id = r.id
                  JOIN availability_slots s ON s.entry_id = e.id
                 WHERE r.period_id = ? AND r.status = 'submitted'
                """, (rs, i) -> new SubmittedSlot(rs.getString("user_id"),
                rs.getObject("available_date", LocalDate.class),
                rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class),
                rs.getString("slot_type")), periodId);
    }
}
