package org.dacnpm.schedulemanager.api.events;

import org.dacnpm.schedulemanager.api.notifications.NotificationRepository;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BAO-01 trên SQL Server thật: gửi lại sự kiện dead không tạo thông báo trùng, có audit,
 * và không gửi lại được sự kiện đã xử lý. Mọi dữ liệu được rollback sau test.
 */
@SpringBootTest
@ActiveProfiles({"sqlserver", "test"})
@EnabledIfEnvironmentVariable(named = "SM_RUN_SQL_TESTS", matches = "true")
@Transactional
class OutboxAdminIT {

    static final CurrentUser ADMIN = new CurrentUser("usr_admin_001", true);
    static final CurrentUser EMPLOYEE = new CurrentUser("bao01_it_emp", false);
    static final String EVENT = "bao01.it:evt_1:v1";
    static final String REASON = "Kiểm thử gửi lại sau sự cố";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".local-test/application-testdb.properties"))) {
            p.load(reader);
        }
        if (!p.getProperty("spring.datasource.url", "").contains("databaseName=ScheduleManager_MocA_Test_")) {
            throw new IllegalStateException("Integration tests require the isolated MocA test database");
        }
        for (String key : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password")) {
            registry.add(key, () -> p.getProperty(key));
        }
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.config.import", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        registry.add("app.scheduling.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxAdminService service;
    @Autowired NotificationRepository notifications;

    @BeforeEach
    void deadEventWithOneDeliveredNotification() {
        jdbc.update("""
                INSERT INTO domain_events (id, event_type, entity_type, entity_id, entity_version, actor_id,
                                           recipients_json, title, body, link_route, status, attempts, last_error)
                VALUES (?, 'leave.approved', 'leave_request', 'bao01_it_leave', 1, NULL,
                        ?, N'Đơn nghỉ đã duyệt', N'Kiểm thử BAO-01', 'leave', 'dead', 8, N'SQL timeout')
                """, EVENT, "[\"usr_admin_001\"]");
        // Lần thử trước đã tạo được thông báo cho người nhận rồi mới lỗi.
        notifications.insertIfAbsent(EVENT, "usr_admin_001", "leave.approved", "Đơn nghỉ đã duyệt",
                "Kiểm thử BAO-01", "leave_request", "bao01_it_leave", "leave");
    }

    @Test
    void retryDeadEvent_processesWithoutDuplicateNotification_andIsAudited() {
        OutboxEventView result = service.retry(ADMIN, EVENT, REASON, true);

        assertThat(result.status()).isEqualTo("processed");
        assertThat(result.notificationsCreated()).isEqualTo(1);
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM history WHERE event_type = 'outbox_event_retry' AND detail = ? AND reason = ?",
                Integer.class, EVENT, REASON);
        assertThat(audits).isEqualTo(1);

        assertThatThrownBy(() -> service.retry(ADMIN, EVENT, REASON, true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("EVENT_NOT_RETRYABLE");
    }

    @Test
    void listProblemEvents_showsDeadEvent_andEmployeeIsForbidden() {
        OutboxEventPage page = service.list(ADMIN, "dead", "leave.approved", null, null, 0, 100);
        assertThat(page.items()).extracting(OutboxEventView::id).contains(EVENT);
        assertThat(page.statusCounts().get("dead")).isGreaterThanOrEqualTo(1L);

        assertThatThrownBy(() -> service.list(EMPLOYEE, "dead", null, null, null, 0, 20))
                .isInstanceOf(ApiException.class);
    }
}
