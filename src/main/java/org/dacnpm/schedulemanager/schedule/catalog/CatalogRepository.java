package org.dacnpm.schedulemanager.schedule.catalog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Danh mục địa điểm, chức vụ, kỹ năng, chứng chỉ và việc gắn chúng với nhân viên. */
@Profile("sqlserver")
@Repository
public class CatalogRepository {

    /** Tên bảng -> tiền tố id. Chỉ các bảng này được truy cập qua đường dẫn API. */
    static final Map<String, String> TABLES = Map.of(
            "locations", "loc_", "positions", "pos_", "skills", "skl_", "certificates", "crt_");

    public record Item(String id, String name) {
    }

    public record CertificateGrant(String certificateId, LocalDate validUntil) {
    }

    public record UserLinks(Set<String> locationIds, Set<String> positionIds, Set<String> skillIds,
                            List<CertificateGrant> certificates) {
        public UserLinks {
            locationIds = locationIds == null ? Set.of() : Set.copyOf(locationIds);
            positionIds = positionIds == null ? Set.of() : Set.copyOf(positionIds);
            skillIds = skillIds == null ? Set.of() : Set.copyOf(skillIds);
            certificates = certificates == null ? List.of() : List.copyOf(certificates);
        }
    }

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean isKnown(String table) {
        return TABLES.containsKey(table);
    }

    public List<Item> list(String table) {
        requireKnown(table);
        return jdbc.query("SELECT id, name FROM " + table + " ORDER BY name",
                (rs, n) -> new Item(rs.getString("id"), rs.getString("name")));
    }

    public Item create(String table, String name) {
        requireKnown(table);
        String id = TABLES.get(table) + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO " + table + " (id, name) VALUES (?, ?)", id, name);
        return new Item(id, name);
    }

    /** Ném DataIntegrityViolationException nếu mục đang được ca hoặc nhân viên sử dụng. */
    public boolean delete(String table, String id) {
        requireKnown(table);
        return jdbc.update("DELETE FROM " + table + " WHERE id = ?", id) > 0;
    }

    public boolean userExists(String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    public UserLinks userLinks(String userId) {
        Set<String> locations = new HashSet<>(jdbc.queryForList(
                "SELECT location_id FROM user_locations WHERE user_id = ?", String.class, userId));
        Set<String> positions = new HashSet<>(jdbc.queryForList(
                "SELECT position_id FROM user_positions WHERE user_id = ?", String.class, userId));
        Set<String> skills = new HashSet<>(jdbc.queryForList(
                "SELECT skill_id FROM user_skills WHERE user_id = ?", String.class, userId));
        List<CertificateGrant> certificates = jdbc.query(
                "SELECT certificate_id, valid_until FROM user_certificates WHERE user_id = ? ORDER BY certificate_id",
                (rs, n) -> new CertificateGrant(rs.getString("certificate_id"), rs.getObject("valid_until", LocalDate.class)),
                userId);
        return new UserLinks(locations, positions, skills, new ArrayList<>(certificates));
    }

    /** Thay toàn bộ liên kết của nhân viên bằng tập mới, trong một giao dịch. */
    @Transactional
    public void replaceUserLinks(String userId, UserLinks links) {
        for (String table : List.of("user_locations", "user_positions", "user_skills", "user_certificates")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
        }
        links.locationIds().forEach(id -> jdbc.update(
                "INSERT INTO user_locations (user_id, location_id) VALUES (?, ?)", userId, id));
        links.positionIds().forEach(id -> jdbc.update(
                "INSERT INTO user_positions (user_id, position_id) VALUES (?, ?)", userId, id));
        links.skillIds().forEach(id -> jdbc.update(
                "INSERT INTO user_skills (user_id, skill_id) VALUES (?, ?)", userId, id));
        links.certificates().forEach(c -> jdbc.update(
                "INSERT INTO user_certificates (user_id, certificate_id, valid_until) VALUES (?, ?, ?)",
                userId, c.certificateId(), c.validUntil()));
    }

    private static void requireKnown(String table) {
        if (!TABLES.containsKey(table)) {
            throw new IllegalArgumentException("Danh mục không hợp lệ: " + table + ".");
        }
    }
}
