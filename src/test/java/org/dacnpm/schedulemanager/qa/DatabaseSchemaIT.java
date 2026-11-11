package org.dacnpm.schedulemanager.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kiểm thử tầng cơ sở dữ liệu trên SQL Server thật (Flyway V1..V18): đủ bảng, khóa, ràng buộc, chỉ mục lọc,
 * dữ liệu seed và hành vi ON DELETE CASCADE. Mỗi test chạy trong transaction và được rollback.
 *
 * <p>Chỉ chạy khi {@code SM_RUN_SQL_TESTS=true} và {@code .local-test/application-testdb.properties} trỏ tới
 * database QA riêng (tên bắt đầu bằng ScheduleManager_MocA_Test_), giống {@code IntegratedMocAIT}.
 */
@SpringBootTest
@ActiveProfiles({"sqlserver", "test"})
@EnabledIfEnvironmentVariable(named = "SM_RUN_SQL_TESTS", matches = "true")
@Transactional
class DatabaseSchemaIT {

    /** 54 bảng nghiệp vụ tạo bởi V1..V18 (không tính flyway_schema_history). */
    static final Set<String> EXPECTED_TABLES = new TreeSet<>(List.of(
            "absence_records", "assignments", "attendance_adjustment_requests", "attendance_audit",
            "attendance_period_locks", "attendance_policies", "attendance_records", "availability_entries",
            "availability_registrations", "availability_slots", "certificates", "departments", "domain_events",
            "employee_types", "history", "leave_requests", "leave_types", "level_substitutions", "levels", "locations",
            "notification_settings", "notifications", "open_shift_claims", "open_shifts", "period_applicable_users",
            "period_dates", "period_weekdays", "periods", "plan_assignments", "positions", "rule_changes",
            "rule_revision", "rule_settings", "schedule_acknowledgements", "schedule_exceptions",
            "schedule_level_targets", "schedule_plans", "schedule_rules", "shift_certificate_requirements",
            "shift_level_requirements", "shift_position_requirements", "shift_requests", "shift_skill_requirements",
            "shift_user_preferences", "shifts", "skills", "user_blocked_dates", "user_certificates", "user_locations",
            "user_permissions", "user_positions", "user_skills", "users", "work_groups"));

    static final String ADMIN = "usr_admin_001";
    static final LocalDate DAY = LocalDate.of(2030, 1, 7);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".local-test/application-testdb.properties"))) {
            p.load(reader);
        }
        if (!p.getProperty("spring.datasource.url", "").contains("databaseName=ScheduleManager_MocA_Test_")) {
            throw new IllegalStateException("Schema tests require the isolated MocA test database");
        }
        for (String key : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password")) {
            registry.add(key, () -> p.getProperty(key));
        }
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.config.import", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        registry.add("app.scheduling.enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbc;

    // ── Migration và cấu trúc ────────────────────────────────────────────────

    @Test
    void flywayAppliedEighteenVersionedMigrationsWithoutFailure() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL", Integer.class);
        Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Integer.class);
        String latest = jdbc.queryForObject(
                "SELECT TOP 1 version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC",
                String.class);
        assertThat(applied).isEqualTo(18);
        assertThat(failed).isZero();
        assertThat(latest).isEqualTo("18");
    }

    @Test
    void allFiftyFourBusinessTablesExist() {
        Set<String> actual = new TreeSet<>(jdbc.queryForList(
                "SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE='BASE TABLE' "
                        + "AND TABLE_SCHEMA='dbo' AND TABLE_NAME<>'flyway_schema_history'", String.class));
        assertThat(actual).isEqualTo(EXPECTED_TABLES);
    }

    @Test
    void everyTableHasPrimaryKey() {
        List<String> withoutPk = jdbc.queryForList(
                "SELECT t.name FROM sys.tables t WHERE NOT EXISTS (SELECT 1 FROM sys.key_constraints k "
                        + "WHERE k.parent_object_id=t.object_id AND k.type='PK')", String.class);
        assertThat(withoutPk).isEmpty();
    }

    @Test
    void foreignKeysAndCheckConstraintsAreEnabledAndTrusted() {
        assertThat(jdbc.queryForList(
                "SELECT name FROM sys.foreign_keys WHERE is_disabled=1 OR is_not_trusted=1", String.class)).isEmpty();
        assertThat(jdbc.queryForList(
                "SELECT name FROM sys.check_constraints WHERE is_disabled=1 OR is_not_trusted=1", String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.foreign_keys", Integer.class)).isGreaterThan(80);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.check_constraints", Integer.class)).isGreaterThan(60);
    }

    @Test
    void businessCriticalConstraintsAndFilteredIndexesExist() {
        Set<String> constraints = new TreeSet<>(jdbc.queryForList(
                "SELECT name FROM sys.objects WHERE type IN ('C','UQ','F','PK')", String.class));
        assertThat(constraints).contains("ck_users_role", "ck_users_status", "uq_users_username",
                "uq_users_employee_code", "ck_periods_date_range", "ck_periods_status", "ck_shift_requests_not_self",
                "ck_shift_requests_swap_target", "ck_att_times", "ck_att_open_status", "uq_assignments_shift_user",
                "uq_notifications_event_recipient", "ck_users_manager_not_self", "fk_shifts_period");
        List<Map<String, Object>> filtered = jdbc.queryForList(
                "SELECT i.name, i.is_unique, i.filter_definition FROM sys.indexes i WHERE i.has_filter=1");
        Set<String> names = new TreeSet<>();
        filtered.forEach(row -> names.add(String.valueOf(row.get("name"))));
        assertThat(names).contains("ux_attendance_open_user", "ux_att_pending_per_shift_user",
                "ux_shift_requests_open_source", "uq_rule_settings_active");
        assertThat(filtered).allSatisfy(row -> assertThat(row.get("is_unique")).isEqualTo(true));
    }

    // ── Dữ liệu seed ─────────────────────────────────────────────────────────

    @Test
    void seedDataIsPresentAndAdminPasswordMatchesDocumentedCredential() {
        assertThat(jdbc.queryForList("SELECT id FROM levels ORDER BY sort_order", String.class))
                .containsExactly("lvl1", "lvl2", "lvl3", "lvl4");
        Map<String, Object> admin = jdbc.queryForMap(
                "SELECT username, role, status, password_hash FROM users WHERE id=?", ADMIN);
        assertThat(admin.get("username")).isEqualTo("admin");
        assertThat(admin.get("role")).isEqualTo("admin");
        assertThat(admin.get("status")).isEqualTo("active");
        assertThat(new BCryptPasswordEncoder().matches("Admin@123", (String) admin.get("password_hash"))).isTrue();
        assertThat(jdbc.queryForList("SELECT id FROM leave_types ORDER BY sort_order", String.class))
                .containsExactly("leave_annual", "leave_sick", "leave_personal", "leave_unpaid");
        assertThat(jdbc.queryForObject("SELECT requires_document FROM leave_types WHERE id='leave_sick'", Boolean.class))
                .isTrue();
        Map<String, Object> policy = jdbc.queryForMap(
                "SELECT timezone_name, early_checkin_minutes, late_grace_minutes, max_shift_hours "
                        + "FROM attendance_policies WHERE id='default'");
        assertThat(policy).containsEntry("timezone_name", "Asia/Ho_Chi_Minh").containsEntry("early_checkin_minutes", 30)
                .containsEntry("late_grace_minutes", 5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_revision WHERE id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_permissions WHERE user_id=? AND permission='schedule.exception.approve'",
                Integer.class, ADMIN)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_settings WHERE id='main'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level_substitutions", Integer.class)).isEqualTo(6);
    }

    // ── Ràng buộc chặn dữ liệu sai ───────────────────────────────────────────

    @Test
    void userConstraintsRejectInvalidRoleStatusDuplicateUsernameAndSelfManager() {
        assertRejected(() -> insertUser("qa_db_bad_role", "QADB1", "qa_db_bad_role", "superuser", "active"));
        assertRejected(() -> insertUser("qa_db_bad_status", "QADB2", "qa_db_bad_status", "user", "deleted"));
        assertRejected(() -> insertUser("qa_db_dup", "QADB3", "admin", "user", "active"));
        insertUser("qa_db_ok", "QADB4", "qa_db_ok", "user", "active");
        assertRejected(() -> jdbc.update("UPDATE users SET manager_id=id WHERE id='qa_db_ok'"));
        assertRejected(() -> jdbc.update("UPDATE users SET target_rate=250 WHERE id='qa_db_ok'"));
    }

    @Test
    void periodConstraintsRejectReversedDatesUnknownStatusAndBadWeekday() {
        assertRejected(() -> jdbc.update("INSERT INTO periods(id,name,cycle,start_date,end_date,status) "
                + "VALUES('qa_db_p1',N'Sai ngày','weekly',?,?,'draft')", DAY, DAY.minusDays(1)));
        assertRejected(() -> jdbc.update("INSERT INTO periods(id,name,cycle,start_date,end_date,status) "
                + "VALUES('qa_db_p2',N'Sai trạng thái','weekly',?,?,'not_open')", DAY, DAY.plusDays(6)));
        assertRejected(() -> jdbc.update("INSERT INTO periods(id,name,cycle,start_date,end_date,status,open_at,deadline) "
                        + "VALUES('qa_db_p3',N'Hạn trước mở','weekly',?,?,'draft',?,?)", DAY, DAY.plusDays(6),
                LocalDateTime.of(2030, 1, 5, 10, 0), LocalDateTime.of(2030, 1, 4, 10, 0)));
        insertPeriod("qa_db_p4");
        assertRejected(() -> jdbc.update("INSERT INTO period_weekdays(period_id,weekday) VALUES('qa_db_p4',7)"));
    }

    @Test
    void shiftAndAssignmentConstraintsRejectInvalidRowsAndDuplicates() {
        insertPeriod("qa_db_p5");
        assertRejected(() -> jdbc.update("INSERT INTO shifts(id,name,shift_date,start_time,end_time,assignment_mode,"
                + "total_required,status) VALUES('qa_db_s0','x',?,'08:00','12:00','combined',0,'draft')", DAY));
        assertRejected(() -> jdbc.update("INSERT INTO shifts(id,name,shift_date,start_time,end_time,assignment_mode,"
                + "total_required,status) VALUES('qa_db_s0','x',?,'08:00','12:00','random',1,'draft')", DAY));
        assertRejected(() -> jdbc.update("INSERT INTO shifts(id,name,shift_date,start_time,end_time,assignment_mode,"
                + "total_required,status,period_id) VALUES('qa_db_s0','x',?,'08:00','12:00','combined',1,'draft','nope')", DAY));
        insertShift("qa_db_s1", "qa_db_p5");
        assertRejected(() -> jdbc.update("UPDATE shifts SET min_required=5, total_required=2 WHERE id='qa_db_s1'"));
        assertRejected(() -> jdbc.update("UPDATE shifts SET break_minutes=-1 WHERE id='qa_db_s1'"));
        assertRejected(() -> jdbc.update("UPDATE shifts SET cancelled_at=SYSUTCDATETIME(), cancel_reason=NULL WHERE id='qa_db_s1'"));
        insertUser("qa_db_u1", "QADB5", "qa_db_u1", "user", "active");
        assign("qa_db_a1", "qa_db_s1", "qa_db_u1");
        assertRejected(() -> assign("qa_db_a2", "qa_db_s1", "qa_db_u1"));
        assertRejected(() -> assign("qa_db_a3", "qa_db_s1", "user_does_not_exist"));
        assertRejected(() -> jdbc.update("UPDATE assignments SET status='deleted' WHERE id='qa_db_a1'"));
    }

    @Test
    void attendanceAllowsOnlyOneOpenPunchPerUserAndConsistentTimes() {
        insertPeriod("qa_db_p6");
        insertShift("qa_db_s2", "qa_db_p6");
        insertShift("qa_db_s3", "qa_db_p6");
        insertUser("qa_db_u2", "QADB6", "qa_db_u2", "user", "active");
        LocalDateTime in = LocalDateTime.of(2030, 1, 7, 8, 0);
        jdbc.update("INSERT INTO attendance_records(id,user_id,shift_id,clock_in_at) VALUES('qa_db_r1','qa_db_u2','qa_db_s2',?)", in);
        assertRejected(() -> jdbc.update("INSERT INTO attendance_records(id,user_id,shift_id,clock_in_at) "
                + "VALUES('qa_db_r2','qa_db_u2','qa_db_s3',?)", in.plusHours(1)));
        assertRejected(() -> jdbc.update("UPDATE attendance_records SET clock_out_at=?, status='completed' WHERE id='qa_db_r1'",
                in.minusHours(1)));
        assertRejected(() -> jdbc.update("UPDATE attendance_records SET clock_out_at=?, status='open' WHERE id='qa_db_r1'",
                in.plusHours(4)));
        jdbc.update("UPDATE attendance_records SET clock_out_at=?, status='completed' WHERE id='qa_db_r1'", in.plusHours(4));
        jdbc.update("INSERT INTO attendance_records(id,user_id,shift_id,clock_in_at) VALUES('qa_db_r2','qa_db_u2','qa_db_s3',?)",
                in.plusHours(5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_records WHERE user_id='qa_db_u2'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void shiftRequestsAllowOnlyOneOpenRequestPerSourceAssignmentAndValidTargets() {
        insertPeriod("qa_db_p7");
        insertShift("qa_db_s4", "qa_db_p7");
        insertUser("qa_db_u3", "QADB7", "qa_db_u3", "user", "active");
        insertUser("qa_db_u4", "QADB8", "qa_db_u4", "user", "active");
        assign("qa_db_a4", "qa_db_s4", "qa_db_u3");
        String sql = "INSERT INTO shift_requests(id,request_type,requester_id,source_assignment_id,source_shift_id,"
                + "target_user_id,target_assignment_id,target_shift_id,reason,status) VALUES(?,?,?,?,?,?,?,?,N'QA',?)";
        jdbc.update(sql, "qa_db_q1", "cover", "qa_db_u3", "qa_db_a4", "qa_db_s4", "qa_db_u4", null, null, "pending_peer");
        assertRejected(() -> jdbc.update(sql, "qa_db_q2", "cover", "qa_db_u3", "qa_db_a4", "qa_db_s4", "qa_db_u4", null, null,
                "pending_approval"));
        jdbc.update("UPDATE shift_requests SET status='cancelled' WHERE id='qa_db_q1'");
        jdbc.update(sql, "qa_db_q2", "cover", "qa_db_u3", "qa_db_a4", "qa_db_s4", "qa_db_u4", null, null, "pending_peer");
        assertRejected(() -> jdbc.update(sql, "qa_db_q3", "cover", "qa_db_u3", "qa_db_a4", "qa_db_s4", "qa_db_u3", null, null,
                "cancelled"));
        assertRejected(() -> jdbc.update(sql, "qa_db_q4", "swap", "qa_db_u3", "qa_db_a4", "qa_db_s4", "qa_db_u4", null, null,
                "cancelled"));
    }

    @Test
    void leaveOpenShiftAndJsonConstraintsRejectInvalidRows() {
        insertUser("qa_db_u5", "QADB9", "qa_db_u5", "user", "active");
        assertRejected(() -> jdbc.update("INSERT INTO leave_requests(id,user_id,leave_type_id,start_date,end_date,reason,status) "
                + "VALUES('qa_db_l1','qa_db_u5','leave_annual',?,?,N'QA','pending')", DAY, DAY.minusDays(1)));
        assertRejected(() -> jdbc.update("INSERT INTO leave_requests(id,user_id,leave_type_id,start_date,end_date,reason,status) "
                + "VALUES('qa_db_l2','qa_db_u5','leave_annual',?,?,N'QA','approved_twice')", DAY, DAY));
        insertPeriod("qa_db_p8");
        insertShift("qa_db_s5", "qa_db_p8");
        assertRejected(() -> jdbc.update("INSERT INTO open_shifts(id,shift_id,slots,filled_count,claim_deadline,status,created_by) "
                + "VALUES('qa_db_o1','qa_db_s5',1,2,?,'open',?)", LocalDateTime.of(2030, 1, 6, 8, 0), ADMIN));
        assertRejected(() -> jdbc.update("INSERT INTO history(id,event_type,title,actor_name,reason,before_json) "
                + "VALUES('qa_db_h1','qa',N'QA',N'QA',N'QA','{not json')"));
        assertRejected(() -> jdbc.update("INSERT INTO rule_settings(id,param_key,scope_type,scope_id,value_text,effective_from,"
                + "change_id,created_by) VALUES('qa_db_rs1','target_hours','company','x','60',?,'missing',?)", DAY, ADMIN));
    }

    // ── Xóa dây chuyền ───────────────────────────────────────────────────────

    @Test
    void deletingPeriodAndShiftCascadesToOwnedChildRows() {
        insertPeriod("qa_db_p9");
        jdbc.update("INSERT INTO period_weekdays(period_id,weekday) VALUES('qa_db_p9',1),('qa_db_p9',2)");
        jdbc.update("INSERT INTO period_dates(period_id,calendar_date,date_type) VALUES('qa_db_p9',?,'exempt')", DAY);
        insertShift("qa_db_s6", null);
        jdbc.update("INSERT INTO shift_level_requirements(shift_id,level_id,required_count) VALUES('qa_db_s6','lvl1',1)");
        jdbc.update("DELETE FROM periods WHERE id='qa_db_p9'");
        jdbc.update("DELETE FROM shifts WHERE id='qa_db_s6'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM period_weekdays WHERE period_id='qa_db_p9'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM period_dates WHERE period_id='qa_db_p9'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_level_requirements WHERE shift_id='qa_db_s6'", Integer.class))
                .isZero();
        insertUser("qa_db_u6", "QADB10", "qa_db_u6", "user", "active");
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES('qa_db_u6','qa.permission')");
        jdbc.update("DELETE FROM users WHERE id='qa_db_u6'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id='qa_db_u6'", Integer.class)).isZero();
    }

    @Test
    void referencedRowsCannotBeDeletedWithoutCascade() {
        insertPeriod("qa_db_p10");
        insertShift("qa_db_s7", "qa_db_p10");
        assertRejected(() -> jdbc.update("DELETE FROM periods WHERE id='qa_db_p10'"));
        assertRejected(() -> jdbc.update("DELETE FROM levels WHERE id='lvl1'"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    void insertUser(String id, String code, String username, String role, String status) {
        jdbc.update("INSERT INTO users(id,employee_code,username,password_hash,full_name,role,status) "
                + "VALUES(?,?,?,'x',N'QA DB',?,?)", id, code, username, role, status);
    }

    void insertPeriod(String id) {
        jdbc.update("INSERT INTO periods(id,name,cycle,start_date,end_date,status) VALUES(?,N'QA DB','weekly',?,?,'draft')",
                id, DAY, DAY.plusDays(6));
    }

    void insertShift(String id, String periodId) {
        jdbc.update("INSERT INTO shifts(id,name,shift_date,start_time,end_time,assignment_mode,total_required,status,period_id) "
                + "VALUES(?,N'QA DB',?,'08:00','12:00','combined',1,'draft',?)", id, DAY, periodId);
    }

    void assign(String id, String shiftId, String userId) {
        jdbc.update("INSERT INTO assignments(id,shift_id,user_id,status,assigned_by) VALUES(?,?,?,'confirmed',?)",
                id, shiftId, userId, ADMIN);
    }

    static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable statement) {
        assertThatThrownBy(statement).isInstanceOf(DataIntegrityViolationException.class);
    }
}
