package org.dacnpm.schedulemanager;

import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Entry;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.SaveRequest;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Slot;
import org.dacnpm.schedulemanager.api.availability.AvailabilityService;
import org.dacnpm.schedulemanager.api.periods.PeriodActionRequest;
import org.dacnpm.schedulemanager.api.periods.PeriodRequest;
import org.dacnpm.schedulemanager.api.periods.PeriodService;
import org.dacnpm.schedulemanager.api.periods.PeriodView;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ApplyRequest;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeItem;
import org.dacnpm.schedulemanager.api.rules.RuleModels.ChangeRequest;
import org.dacnpm.schedulemanager.api.rules.RuleModels.EffectiveRules;
import org.dacnpm.schedulemanager.api.rules.RuleModels.Preview;
import org.dacnpm.schedulemanager.api.rules.RuleService;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.support.MutableClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra tích hợp issue #5 trên SQL Server thật: Flyway V1–V7, giao dịch, khóa dòng, CHECK/FK.
 *
 * <p>Không chạy trong {@code mvn test} (tên *IT, và cần biến môi trường). Chạy thủ công với một
 * database RỖNG dành riêng cho test:
 * <pre>
 * SM_IT_DB_URL=jdbc:sqlserver://localhost:1433;databaseName=ScheduleManager_IT;encrypt=true;trustServerCertificate=true
 * SM_IT_DB_USER=...  SM_IT_DB_PASSWORD=...
 * mvn test -Dtest=IssueFiveFlowIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "SM_IT_DB_URL", matches = ".+")
@SpringBootTest
@ActiveProfiles("sqlserver")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IssueFiveFlowIT {

    private static final CurrentUser ADMIN = new CurrentUser("usr_admin_001", true);
    private static final CurrentUser NV1 = new CurrentUser("it_nv1", false);
    private static final CurrentUser NV2 = new CurrentUser("it_nv2", false);
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 25, 9, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 5, 23, 59);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("SM_IT_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv("SM_IT_DB_USER"));
        r.add("spring.datasource.password", () -> System.getenv("SM_IT_DB_PASSWORD"));
        r.add("spring.datasource.driver-class-name", () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.config.import", () -> "");
        r.add("app.jwt.secret", () -> "integration-test-secret-at-least-32-characters");
        r.add("app.scheduling.enabled", () -> "false");
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.atVn(START);
        }
    }

    @Autowired private PeriodService periods;
    @Autowired private AvailabilityService availability;
    @Autowired private RuleService rules;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private String periodId;

    @BeforeAll
    void seedEmployees() {
        assertEquals(0, count("SELECT COUNT(*) FROM periods"), "Cần database rỗng dành riêng cho test tích hợp.");
        jdbc.update("""
                INSERT INTO users (id, employee_code, username, password_hash, full_name, role, level_id, status)
                VALUES ('it_nv1', 'IT001', 'it_nv1', 'x', N'Nhân viên IT 1', 'user', 'lvl1', 'active'),
                       ('it_nv2', 'IT002', 'it_nv2', 'x', N'Nhân viên IT 2', 'user', 'lvl2', 'active'),
                       ('it_nv3', 'IT003', 'it_nv3', 'x', N'Nhân viên IT 3', 'user', 'lvl2', 'active')
                """);
    }

    @Test
    @Order(1)
    void migrationV7_constraintsAreInPlace() {
        assertEquals("7", jdbc.queryForObject(
                "SELECT TOP 1 version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC", String.class));
        String check = jdbc.queryForObject(
                "SELECT definition FROM sys.check_constraints WHERE name = 'ck_periods_status'", String.class);
        assertTrue(check.contains("archived") && !check.contains("not_open"), check);
        assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO periods (id, name, cycle, start_date, end_date, status) VALUES ('bad', N'x', 'monthly',
                '2026-01-01', '2026-01-31', 'not_open')
                """), "CHECK phải chặn trạng thái cũ not_open");
    }

    @Test
    @Order(2)
    void createAndOpenPeriod_onlyApplicableEmployeesSeeIt() {
        PeriodView created = periods.create(ADMIN, new PeriodRequest("Kỳ tháng 10/2026 — IT", "monthly",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), LocalDateTime.of(2026, 9, 20, 8, 0), DEADLINE,
                true, true, List.of(1, 2, 3, 4, 5, 6), List.of(LocalDate.of(2026, 10, 10)), List.of(),
                List.of("it_nv1", "it_nv2"), null));
        periodId = created.id();
        assertEquals("draft", created.status());
        assertTrue(periods.list(NV1).isEmpty(), "Kỳ nháp không hiện với nhân viên");

        PeriodView opened = periods.open(ADMIN, periodId, PeriodActionRequest.empty());
        assertEquals("Đang mở đăng ký — hạn chót 05/10/2026 23:59", opened.statusLabel());
        assertEquals(1, periods.list(NV1).size());
        assertTrue(periods.list(new CurrentUser("it_nv3", false)).isEmpty(), "it_nv3 không thuộc đối tượng áp dụng");
        assertEquals(LocalDateTime.of(2026, 10, 5, 16, 59), jdbc.queryForObject(
                "SELECT deadline FROM periods WHERE id = ?", LocalDateTime.class, periodId), "DB lưu UTC");
    }

    @Test
    @Order(3)
    void beforeDeadline_saveDraftThenSubmit() {
        availability.saveMine(NV1, periodId, request(LocalDate.of(2026, 10, 6), "07:00", "15:00", null));
        assertEquals("draft", status("it_nv1"));
        availability.submitMine(NV1, periodId, new SaveRequest(List.of(
                new Entry(LocalDate.of(2026, 10, 6), List.of(slot("07:00", "12:00", null), slot("13:00", "17:00", "unavailable"))),
                new Entry(LocalDate.of(2026, 10, 7), List.of(slot("00:00", "23:59", null))))));
        assertEquals("submitted", status("it_nv1"));
        assertEquals(3, count("SELECT COUNT(*) FROM availability_slots s JOIN availability_entries e ON e.id = s.entry_id "
                + "JOIN availability_registrations r ON r.id = e.registration_id WHERE r.user_id = 'it_nv1'"));
        var summary = availability.summary(ADMIN, periodId);
        var oct7 = summary.days().stream().filter(d -> d.date().equals(LocalDate.of(2026, 10, 7))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("24").compareTo(oct7.availableHours()));
        assertEquals(1, availability.progress(ADMIN, periodId).submitted());
    }

    @Test
    @Order(4)
    void ac04_ac05_afterDeadline_apiRejectsEvenBeforeAutoLockJobRuns() {
        clock().setVn(DEADLINE.minusMinutes(2));
        assertTrue(availability.getMine(NV2, periodId).editable(), "form mở trước hạn");
        clock().setVn(DEADLINE.plusMinutes(1)); // gửi sau hạn; job tự khóa CHƯA chạy

        assertEquals("open", jdbc.queryForObject("SELECT status FROM periods WHERE id = ?", String.class, periodId));
        ApiException submit = assertThrows(ApiException.class,
                () -> availability.submitMine(NV2, periodId, request(LocalDate.of(2026, 10, 6), "08:00", "12:00", null)));
        assertEquals("REGISTRATION_CLOSED", submit.getCode());
        assertThrows(ApiException.class,
                () -> availability.saveMine(NV1, periodId, request(LocalDate.of(2026, 10, 8), "08:00", "12:00", null)));
        assertEquals(0, count("SELECT COUNT(*) FROM availability_registrations WHERE user_id = 'it_nv2'"));
        assertEquals("submitted", status("it_nv1"), "đăng ký đã gửi giữ nguyên");
        assertFalse(availability.getMine(NV1, periodId).editable());
    }

    @Test
    @Order(5)
    void autoLockJob_locksExpiredPeriod_andAuditsAsSystem() {
        assertEquals(1, periods.autoLockExpired());
        assertEquals("locked", jdbc.queryForObject("SELECT status FROM periods WHERE id = ?", String.class, periodId));
        assertEquals(1, count("SELECT COUNT(*) FROM history WHERE event_type = 'period.auto_locked' AND actor_id IS NULL"));
        assertEquals(0, periods.autoLockExpired(), "chạy lại không khóa lặp");
    }

    @Test
    @Order(6)
    void ac06_reopen_requiresPermissionConfirmReason_andKeepsRegistrations() {
        LocalDateTime newDeadline = LocalDateTime.of(2026, 10, 8, 23, 59);
        assertThrows(ApiException.class, () -> periods.reopen(NV1, periodId, new PeriodActionRequest(null, true, "x", newDeadline)));
        assertThrows(ApiException.class, () -> periods.reopen(ADMIN, periodId, new PeriodActionRequest(null, true, null, newDeadline)));
        assertThrows(ApiException.class, () -> periods.reopen(ADMIN, periodId, new PeriodActionRequest(null, false, "x", newDeadline)));

        PeriodView reopened = periods.reopen(ADMIN, periodId,
                new PeriodActionRequest(null, true, "Bổ sung nhân viên mới", newDeadline));
        assertEquals("open", reopened.status());
        assertEquals("Đang mở đăng ký — hạn chót 08/10/2026 23:59", reopened.statusLabel());
        assertEquals("submitted", status("it_nv1"));
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_id, reason FROM history WHERE event_type = 'period.reopened'");
        assertEquals("usr_admin_001", audit.get("actor_id"));
        assertEquals("Bổ sung nhân viên mới", audit.get("reason"));

        availability.submitMine(NV2, periodId, request(LocalDate.of(2026, 10, 8), "08:00", "12:00", null));
        assertEquals("submitted", status("it_nv2"));
        assertEquals(2, availability.submittedForScheduling(periodId).size(), "nguồn dữ liệu nội bộ cho phân lịch");
    }

    @Test
    @Order(7)
    void ac13_ac14_rulesPreviewConfirm_derivesMax_andNeverTouchesSchedules() {
        Map<String, Object> before = scheduleCounts();
        EffectiveRules empty = rules.effective(ADMIN, "it_nv1", null);
        assertFalse(empty.readyForScheduling());
        assertEquals(6, empty.missing().size(), "chưa cấu hình → checklist, không tự gán số");

        Preview preview = rules.preview(ADMIN, new ChangeRequest(List.of(
                set("target_hours", "60"), set("max_hours_mode", "percent"), set("max_hours_percent", "125"),
                set("max_hours_per_day", "12"), set("max_hours_per_week", "48"), set("min_rest_hours", "8"),
                set("max_consecutive_days", "6")), "Cấu hình ban đầu"));
        assertEquals(3, preview.affectedUsers());
        assertEquals(0, count("SELECT COUNT(*) FROM rule_settings"), "xem trước không ghi giá trị");

        rules.apply(ADMIN, preview.changeId(), new ApplyRequest(true));
        EffectiveRules effective = rules.effective(NV1, null, null);
        assertTrue(effective.readyForScheduling());
        assertEquals(0, new BigDecimal("75").compareTo(effective.derived().maxHours()));
        assertEquals("60 × 125% = 75", effective.derived().maxHoursFormula());

        Preview override = rules.preview(ADMIN, new ChangeRequest(List.of(
                new ChangeItem("set", "target_hours", "user", "it_nv2", "40", null)), "Bán thời gian"));
        Preview stale = rules.preview(ADMIN, new ChangeRequest(List.of(set("max_hours_percent", "130")), "Khác"));
        rules.apply(ADMIN, override.changeId(), new ApplyRequest(true));
        assertEquals("STALE_PREVIEW", assertThrows(ApiException.class,
                () -> rules.apply(ADMIN, stale.changeId(), new ApplyRequest(true))).getCode());
        assertEquals(0, new BigDecimal("50").compareTo(rules.effective(ADMIN, "it_nv2", null).derived().maxHours()));

        assertEquals(2, rules.history(ADMIN, null, null).size());
        assertEquals(before, scheduleCounts(), "áp dụng quy tắc không sửa kỳ/ca/phân công");
        assertEquals(1, count("SELECT COUNT(*) FROM history WHERE event_type = 'rules.applied' AND reason = N'Bán thời gian'"));
    }

    @Test
    @Order(8)
    void lockArchive_andDeleteOnlyDraft() {
        periods.lock(ADMIN, periodId, PeriodActionRequest.empty());
        periods.archive(ADMIN, periodId, PeriodActionRequest.empty());
        assertEquals("archived", jdbc.queryForObject("SELECT status FROM periods WHERE id = ?", String.class, periodId));
        assertEquals("INVALID_TRANSITION", assertThrows(ApiException.class,
                () -> periods.delete(ADMIN, periodId)).getCode());
    }

    private MutableClock clock() {
        return (MutableClock) clock;
    }

    private Map<String, Object> scheduleCounts() {
        return jdbc.queryForMap("SELECT (SELECT COUNT(*) FROM periods) AS p, (SELECT COUNT(*) FROM shifts) AS s, "
                + "(SELECT COUNT(*) FROM assignments) AS a, (SELECT MAX(version) FROM periods) AS v");
    }

    private String status(String userId) {
        return jdbc.queryForObject("SELECT status FROM availability_registrations WHERE user_id = ? AND period_id = ?",
                String.class, userId, periodId);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private static SaveRequest request(LocalDate date, String start, String end, String type) {
        return new SaveRequest(List.of(new Entry(date, List.of(slot(start, end, type)))));
    }

    private static Slot slot(String start, String end, String type) {
        return new Slot(LocalTime.parse(start), LocalTime.parse(end), type);
    }

    private static ChangeItem set(String key, String value) {
        return new ChangeItem("set", key, "company", null, value, null);
    }
}
