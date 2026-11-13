package org.dacnpm.schedulemanager.api.events;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Truy vấn/cập nhật bảng outbox {@code domain_events} cho Admin (BAO-01). */
@Profile("sqlserver")
@Repository
public class OutboxEventRepository {

    private static final String SELECT = """
            SELECT e.id, e.event_type, e.entity_type, e.entity_id, e.entity_version, e.actor_id, e.title,
                   e.status, e.attempts, e.occurred_at, e.next_attempt_at, e.processed_at, e.last_error,
                   (SELECT COUNT(*) FROM notifications n WHERE n.event_id = e.id) AS notifications_created
            FROM domain_events e
            """;

    private static final RowMapper<OutboxEventView> MAPPER = (rs, i) -> new OutboxEventView(
            rs.getString("id"),
            rs.getString("event_type"),
            rs.getString("entity_type"),
            rs.getString("entity_id"),
            rs.getInt("entity_version"),
            rs.getString("actor_id"),
            rs.getString("title"),
            rs.getString("status"),
            rs.getInt("attempts"),
            toLocal(rs.getTimestamp("occurred_at")),
            toLocal(rs.getTimestamp("next_attempt_at")),
            toLocal(rs.getTimestamp("processed_at")),
            rs.getString("last_error"),
            rs.getLong("notifications_created"));

    private final JdbcTemplate jdbc;

    public OutboxEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OutboxEventView> find(OutboxEventFilter filter, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = where(filter, params);
        params.add(offset);
        params.add(limit);
        // Mới nhất trước; id làm khóa phụ để phân trang ổn định.
        return jdbc.query(SELECT + where + " ORDER BY e.occurred_at DESC, e.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                MAPPER, params.toArray());
    }

    public long count(OutboxEventFilter filter) {
        List<Object> params = new ArrayList<>();
        String where = where(filter, params);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM domain_events e" + where, Long.class, params.toArray());
        return n == null ? 0 : n;
    }

    /** Số sự kiện theo trạng thái trên toàn bảng; trạng thái không có dòng nào trả 0. */
    public Map<String, Long> countByStatus() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String s : OutboxAdminService.ALL_STATUSES) {
            result.put(s, 0L);
        }
        jdbc.query("SELECT status, COUNT(*) AS n FROM domain_events GROUP BY status",
                rs -> { result.put(rs.getString("status"), rs.getLong("n")); });
        return result;
    }

    public OutboxEventView findById(String id) {
        List<OutboxEventView> rows = jdbc.query(SELECT + " WHERE e.id = ?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Đọc và khóa dòng sự kiện trong giao dịch hiện tại (UPDLOCK) để dispatcher và Admin khác
     * không đổi trạng thái chen giữa lúc kiểm tra và lúc cập nhật.
     */
    public OutboxEventView lockById(String id) {
        List<OutboxEventView> rows = jdbc.query(SELECT.replace("FROM domain_events e",
                "FROM domain_events e WITH (UPDLOCK, ROWLOCK)") + " WHERE e.id = ?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Lấy id các sự kiện cần gửi lại theo trạng thái/loại, tối đa {@code limit}, có khóa dòng.
     */
    public List<String> lockIdsForRetry(List<String> statuses, String type, int limit) {
        List<Object> params = new ArrayList<>();
        params.add(limit);
        StringBuilder sql = new StringBuilder(
                "SELECT TOP (?) id FROM domain_events WITH (UPDLOCK, ROWLOCK) WHERE status IN (");
        appendPlaceholders(sql, statuses, params);
        sql.append(")");
        if (type != null) {
            sql.append(" AND event_type = ?");
            params.add(type);
        }
        sql.append(" ORDER BY occurred_at");
        return jdbc.queryForList(sql.toString(), String.class, params.toArray());
    }

    /**
     * Đưa sự kiện về hàng đợi: {@code pending}, đặt lại số lần thử, thử ngay.
     * Giữ {@code last_error} để còn dấu vết lỗi trước đó cho tới khi gửi thành công.
     *
     * @return số dòng cập nhật (0 nếu trạng thái đã khác {@code expectedStatus})
     */
    public int resetForRetry(String id, String expectedStatus) {
        return jdbc.update("""
                UPDATE domain_events
                SET status = 'pending', attempts = 0, next_attempt_at = SYSUTCDATETIME()
                WHERE id = ? AND status = ?
                """, id, expectedStatus);
    }

    private static String where(OutboxEventFilter filter, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (!filter.statuses().isEmpty()) {
            sql.append(" AND e.status IN (");
            appendPlaceholders(sql, filter.statuses(), params);
            sql.append(")");
        }
        if (filter.type() != null) {
            sql.append(" AND e.event_type = ?");
            params.add(filter.type());
        }
        if (filter.fromUtc() != null) {
            sql.append(" AND e.occurred_at >= ?");
            params.add(Timestamp.valueOf(filter.fromUtc()));
        }
        if (filter.toUtc() != null) {
            sql.append(" AND e.occurred_at < ?");
            params.add(Timestamp.valueOf(filter.toUtc()));
        }
        return sql.toString();
    }

    private static void appendPlaceholders(StringBuilder sql, List<String> values, List<Object> params) {
        for (int i = 0; i < values.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            params.add(values.get(i));
        }
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
