package org.dacnpm.schedulemanager.schedule.shift;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Truy cập bảng shifts và các bảng yêu cầu của ca. Khóa lạc quan theo cột version. */
@Profile("sqlserver")
@Repository
public class ShiftRepository {

    private static final int CHUNK = 500;
    /** Chỉ cho phép các bảng này trong truy vấn kiểm tra id tồn tại (tên bảng không đến từ người dùng). */
    private static final Set<String> LOOKUP_TABLES = Set.of("locations", "positions", "skills", "certificates", "users", "levels");

    /** Các cột của bảng shifts mà repository đọc; test đối chiếu danh sách này với migration. */
    static final List<String> SHIFT_COLUMNS = List.of(
            "id", "name", "shift_date", "start_time", "end_time", "break_minutes", "location_id", "assignment_mode",
            "total_required", "min_required", "max_allowed", "note", "status", "version", "is_emergency", "series_id",
            "cancelled_at", "cancelled_by", "cancel_reason");

    private static final String SELECT_SHIFT = "SELECT " + String.join(", ", SHIFT_COLUMNS) + " FROM shifts ";

    private final JdbcTemplate jdbc;

    public ShiftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Header(String id, ShiftRequest base, String status, int version, boolean emergency,
                          String seriesId, Instant cancelledAt, String cancelledBy, String cancelReason) {
    }

    private record Children(Map<String, Map<String, Integer>> levels, Map<String, Set<String>> mandatory,
                            Map<String, Set<String>> preferred, Map<String, Set<String>> skills,
                            Map<String, Set<String>> certificates, Map<String, Set<String>> positions) {
    }

    public void insert(String id, ShiftRequest r, boolean emergency, String seriesId) {
        jdbc.update("""
                INSERT INTO shifts (id, name, shift_date, start_time, end_time, assignment_mode, total_required, note,
                                    status, break_minutes, version, location_id, min_required, max_allowed,
                                    is_emergency, series_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'not_scheduled', ?, 1, ?, ?, ?, ?, ?)
                """,
                id, r.name().trim(), r.date(), r.startTime(), r.endTime(), r.mode(), r.totalRequired(), r.note(),
                r.breakMinutes(), r.locationId(), r.minRequired(), r.maxAllowed(), emergency, seriesId);
        writeChildren(id, r);
    }

    /** Cập nhật có điều kiện theo version; false nếu version đã đổi, ca không tồn tại hoặc đã hủy. */
    public boolean update(String id, int expectedVersion, ShiftRequest r) {
        int updated = jdbc.update("""
                UPDATE shifts
                SET name = ?, shift_date = ?, start_time = ?, end_time = ?, assignment_mode = ?, total_required = ?,
                    note = ?, break_minutes = ?, location_id = ?, min_required = ?, max_allowed = ?,
                    version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND cancelled_at IS NULL
                """,
                r.name().trim(), r.date(), r.startTime(), r.endTime(), r.mode(), r.totalRequired(), r.note(),
                r.breakMinutes(), r.locationId(), r.minRequired(), r.maxAllowed(), id, expectedVersion);
        if (updated == 0) {
            return false;
        }
        writeChildren(id, r);
        return true;
    }

    public void setSeries(String id, String seriesId) {
        jdbc.update("UPDATE shifts SET series_id = ? WHERE id = ? AND series_id IS NULL", seriesId, id);
    }

    /**
     * Hủy ca và hủy các phân công đang hiệu lực của ca (không tính vào tổng giờ nữa).
     *
     * @return số phân công bị hủy, hoặc -1 nếu version đã đổi / ca đã hủy
     */
    public int cancel(String id, int expectedVersion, String actorId, String reason) {
        int updated = jdbc.update("""
                UPDATE shifts
                SET cancelled_at = SYSUTCDATETIME(), cancelled_by = ?, cancel_reason = ?,
                    version = version + 1, updated_at = SYSUTCDATETIME()
                WHERE id = ? AND version = ? AND cancelled_at IS NULL
                """, actorId, reason, id, expectedVersion);
        if (updated == 0) {
            return -1;
        }
        return jdbc.update("UPDATE assignments SET status = 'cancelled' WHERE shift_id = ? AND status = 'confirmed'", id);
    }

    public Set<String> confirmedAssignees(String shiftId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM assignments WHERE shift_id = ? AND status = 'confirmed'", String.class, shiftId));
    }

    /** Số dòng phân công (mọi trạng thái) và phân công nháp của ca; ca đã có dữ liệu này không được xóa. */
    public int countReferences(String shiftId) {
        Integer assignments = jdbc.queryForObject("SELECT COUNT(*) FROM assignments WHERE shift_id = ?", Integer.class, shiftId);
        Integer drafts = jdbc.queryForObject("SELECT COUNT(*) FROM plan_assignments WHERE shift_id = ?", Integer.class, shiftId);
        return (assignments == null ? 0 : assignments) + (drafts == null ? 0 : drafts);
    }

    public boolean delete(String shiftId) {
        return jdbc.update("DELETE FROM shifts WHERE id = ?", shiftId) > 0;
    }

    public Optional<ShiftDetail> findById(String id) {
        List<Header> headers = jdbc.query(SELECT_SHIFT + " WHERE id = ?", (rs, n) -> header(rs), id);
        return assemble(headers).stream().findFirst();
    }

    public List<ShiftDetail> search(LocalDate from, LocalDate to, String locationId, boolean includeCancelled) {
        StringBuilder sql = new StringBuilder(SELECT_SHIFT).append(" WHERE shift_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(from, to));
        if (locationId != null && !locationId.isBlank()) {
            sql.append(" AND location_id = ?");
            args.add(locationId);
        }
        if (!includeCancelled) {
            sql.append(" AND cancelled_at IS NULL");
        }
        sql.append(" ORDER BY shift_date, start_time, id");
        return assemble(jdbc.query(sql.toString(), (rs, n) -> header(rs), args.toArray()));
    }

    /** Kỳ chứa ngày này (kỳ bắt đầu sớm nhất nếu trùng nhiều kỳ); rỗng nếu không có kỳ nào. */
    public Optional<String> periodIdFor(LocalDate date) {
        return jdbc.queryForList("SELECT TOP 1 id FROM periods WHERE ? BETWEEN start_date AND end_date ORDER BY start_date, id",
                String.class, date).stream().findFirst();
    }

    /** Các id không tồn tại trong bảng tra cứu (bảng phải thuộc danh sách cho phép). */
    public List<String> missingIds(String table, Collection<String> ids) {
        if (!LOOKUP_TABLES.contains(table)) {
            throw new IllegalArgumentException("Bảng không được phép tra cứu: " + table);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> all = new ArrayList<>(ids);
        Set<String> found = new HashSet<>();
        for (List<String> chunk : partition(all)) {
            found.addAll(jdbc.queryForList("SELECT id FROM " + table + " WHERE id IN (" + placeholders(chunk.size()) + ")",
                    String.class, chunk.toArray()));
        }
        return all.stream().filter(id -> !found.contains(id)).sorted().toList();
    }

    public void writeHistory(String eventType, String title, String detail, String actorId,
                             String beforeJson, String afterJson, String reason) {
        String actorName = jdbc.queryForList("SELECT full_name FROM users WHERE id = ?", String.class, actorId)
                .stream().findFirst().orElse(actorId);
        jdbc.update("""
                INSERT INTO history (id, event_type, title, detail, actor_id, actor_name, before_json, after_json, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "hist_" + UUID.randomUUID().toString().replace("-", ""), eventType, title, detail, actorId, actorName,
                beforeJson, afterJson, reason);
    }

    private void writeChildren(String id, ShiftRequest r) {
        for (String table : List.of("shift_level_requirements", "shift_user_preferences", "shift_skill_requirements",
                "shift_certificate_requirements", "shift_position_requirements")) {
            jdbc.update("DELETE FROM " + table + " WHERE shift_id = ?", id);
        }
        r.levelRequirements().forEach((levelId, count) -> jdbc.update(
                "INSERT INTO shift_level_requirements (shift_id, level_id, required_count) VALUES (?, ?, ?)", id, levelId, count));
        r.mandatoryUserIds().forEach(userId -> jdbc.update(
                "INSERT INTO shift_user_preferences (shift_id, user_id, preference_type) VALUES (?, ?, 'mandatory')", id, userId));
        r.preferredUserIds().forEach(userId -> jdbc.update(
                "INSERT INTO shift_user_preferences (shift_id, user_id, preference_type) VALUES (?, ?, 'preferred')", id, userId));
        r.skillIds().forEach(skillId -> jdbc.update(
                "INSERT INTO shift_skill_requirements (shift_id, skill_id) VALUES (?, ?)", id, skillId));
        r.certificateIds().forEach(certificateId -> jdbc.update(
                "INSERT INTO shift_certificate_requirements (shift_id, certificate_id) VALUES (?, ?)", id, certificateId));
        r.positionIds().forEach(positionId -> jdbc.update(
                "INSERT INTO shift_position_requirements (shift_id, position_id) VALUES (?, ?)", id, positionId));
    }

    private Header header(ResultSet rs) throws SQLException {
        ShiftRequest base = new ShiftRequest(rs.getString("name"), rs.getObject("shift_date", LocalDate.class),
                rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
                rs.getInt("break_minutes"), rs.getString("location_id"), rs.getString("assignment_mode"),
                rs.getInt("total_required"),
                rs.getObject("min_required") == null ? null : rs.getInt("min_required"),
                rs.getObject("max_allowed") == null ? null : rs.getInt("max_allowed"),
                rs.getString("note"), null, null, null, null, null, null);
        LocalDateTime cancelled = rs.getObject("cancelled_at", LocalDateTime.class);
        // DATETIME2 lưu theo UTC (SYSUTCDATETIME).
        return new Header(rs.getString("id"), base, rs.getString("status"), rs.getInt("version"),
                rs.getBoolean("is_emergency"), rs.getString("series_id"),
                cancelled == null ? null : cancelled.toInstant(ZoneOffset.UTC),
                rs.getString("cancelled_by"), rs.getString("cancel_reason"));
    }

    private List<ShiftDetail> assemble(List<Header> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        Children c = loadChildren(headers.stream().map(Header::id).toList());
        List<ShiftDetail> result = new ArrayList<>();
        for (Header h : headers) {
            ShiftRequest b = h.base();
            ShiftRequest full = new ShiftRequest(b.name(), b.date(), b.startTime(), b.endTime(), b.breakMinutes(),
                    b.locationId(), b.mode(), b.totalRequired(), b.minRequired(), b.maxAllowed(), b.note(),
                    c.levels().get(h.id()), c.mandatory().get(h.id()), c.preferred().get(h.id()),
                    c.skills().get(h.id()), c.certificates().get(h.id()), c.positions().get(h.id()));
            result.add(new ShiftDetail(h.id(), full, h.status(), h.version(), h.emergency(), h.seriesId(),
                    h.cancelledAt(), h.cancelledBy(), h.cancelReason()));
        }
        return result;
    }

    private Children loadChildren(List<String> ids) {
        Map<String, Map<String, Integer>> levels = new HashMap<>();
        Map<String, Set<String>> mandatory = new HashMap<>();
        Map<String, Set<String>> preferred = new HashMap<>();
        Map<String, Set<String>> skills = new HashMap<>();
        Map<String, Set<String>> certificates = new HashMap<>();
        Map<String, Set<String>> positions = new HashMap<>();
        for (List<String> chunk : partition(ids)) {
            String in = placeholders(chunk.size());
            Object[] args = chunk.toArray();
            jdbc.query("SELECT shift_id, level_id, required_count FROM shift_level_requirements WHERE shift_id IN (" + in + ")",
                    rs -> {
                        levels.computeIfAbsent(rs.getString("shift_id"), k -> new LinkedHashMap<>())
                                .put(rs.getString("level_id"), rs.getInt("required_count"));
                    }, args);
            jdbc.query("SELECT shift_id, user_id, preference_type FROM shift_user_preferences WHERE shift_id IN (" + in + ")",
                    rs -> {
                        Map<String, Set<String>> target = "mandatory".equals(rs.getString("preference_type")) ? mandatory : preferred;
                        target.computeIfAbsent(rs.getString("shift_id"), k -> new HashSet<>()).add(rs.getString("user_id"));
                    }, args);
            collect("SELECT shift_id, skill_id FROM shift_skill_requirements WHERE shift_id IN (" + in + ")", args, skills);
            collect("SELECT shift_id, certificate_id FROM shift_certificate_requirements WHERE shift_id IN (" + in + ")", args, certificates);
            collect("SELECT shift_id, position_id FROM shift_position_requirements WHERE shift_id IN (" + in + ")", args, positions);
        }
        return new Children(levels, mandatory, preferred, skills, certificates, positions);
    }

    private void collect(String sql, Object[] args, Map<String, Set<String>> into) {
        jdbc.query(sql, rs -> {
            into.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
        }, args);
    }

    private static List<List<String>> partition(List<String> items) {
        List<List<String>> parts = new ArrayList<>();
        for (int i = 0; i < items.size(); i += CHUNK) {
            parts.add(items.subList(i, Math.min(items.size(), i + CHUNK)));
        }
        return parts;
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
