package org.dacnpm.schedulemanager.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.dacnpm.schedulemanager.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ma trận bảo mật cho toàn bộ 153 mapping HTTP của ứng dụng (152 endpoint /api và route "/").
 *
 * <p>Nạp đủ context profile {@code sqlserver,test} (H2, Flyway tắt) giống {@code ApplicationWiringTest}; chỉ kiểm tra
 * tầng bảo mật (JWT filter + SecurityFilterChain) nên không cần SQL Server. Phân quyền theo đối tượng trong service
 * (requireAdmin, chủ sở hữu) được kiểm thử ở {@code tests/qa/api-full.mjs} trên backend thật.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"sqlserver", "test"})
class SecurityMatrixTest {

    static final String TEST_SECRET = "test-secret-key-at-least-32-chars-long-for-hmac";

    /** Danh mục trích từ @GetMapping/@PostMapping/... trong src/main/java (biến đường dẫn đã thay bằng giá trị mẫu). */
    static final String CATALOG = """
            GET /
            GET /api/attendance/audit
            POST /api/attendance/check-in
            GET /api/attendance/me
            GET /api/attendance/pending-approval
            POST /api/attendance/periods/qa-period/lock
            GET /api/attendance/records
            GET /api/attendance/requests
            POST /api/attendance/requests
            POST /api/attendance/requests/qa-id/review
            GET /api/attendance/shifts
            POST /api/attendance/qa-record/approve
            POST /api/attendance/qa-record/check-out
            GET /api/audit
            POST /api/auth/change-password
            POST /api/auth/login
            POST /api/auth/logout
            GET /api/availability/my
            PUT /api/availability/my
            POST /api/availability/my/submit
            GET /api/availability/progress
            GET /api/availability/submitted
            GET /api/availability/summary
            GET /api/health
            GET /api/health/database
            GET /api/leave
            POST /api/leave
            POST /api/leave/absences
            GET /api/leave/absences/report
            GET /api/leave/pending-count
            GET /api/leave/types
            GET /api/leave/qa-id
            POST /api/leave/qa-id/approve
            POST /api/leave/qa-id/cancel
            GET /api/leave/qa-id/impact
            POST /api/leave/qa-id/reject
            GET /api/levels
            GET /api/me
            POST /api/my/checks/claim
            GET /api/my/profile
            GET /api/my/schedule
            POST /api/my/schedule/confirm
            POST /api/my/schedule/viewed
            GET /api/notifications
            POST /api/notifications/read-all
            GET /api/notifications/settings
            PUT /api/notifications/settings
            GET /api/notifications/unread-count
            POST /api/notifications/qa-id/read
            GET /api/open-shifts
            POST /api/open-shifts
            GET /api/open-shifts/pending-count
            GET /api/open-shifts/qa-id
            POST /api/open-shifts/qa-id/cancel
            POST /api/open-shifts/qa-id/claims
            DELETE /api/open-shifts/qa-id/claims/mine
            POST /api/open-shifts/qa-id/claims/qa-claim/approve
            POST /api/open-shifts/qa-id/claims/qa-claim/reject
            POST /api/open-shifts/qa-id/close
            GET /api/pending-summary
            GET /api/periods
            POST /api/periods
            DELETE /api/periods/qa-id
            GET /api/periods/qa-id
            PUT /api/periods/qa-id
            POST /api/periods/qa-id/archive
            POST /api/periods/qa-id/lock
            POST /api/periods/qa-id/open
            POST /api/periods/qa-id/reopen
            GET /api/reports/attendance-status
            GET /api/reports/dashboard
            GET /api/reports/export
            GET /api/reports/periods
            GET /api/reports/shortages
            GET /api/reports/users
            GET /api/requests
            POST /api/requests
            GET /api/requests/cover-candidates
            GET /api/requests/pending-count
            GET /api/requests/swap-options
            GET /api/requests/qa-id
            POST /api/requests/qa-id/accept
            POST /api/requests/qa-id/approve
            POST /api/requests/qa-id/cancel
            POST /api/requests/qa-id/decline
            POST /api/requests/qa-id/reject
            GET /api/rules/catalog
            GET /api/rules/changes
            POST /api/rules/changes
            GET /api/rules/changes/qa-id
            POST /api/rules/changes/qa-id/apply
            POST /api/rules/changes/qa-id/cancel
            GET /api/rules/effective
            GET /api/rules/readiness
            GET /api/rules/settings
            GET /api/rules/templates
            GET /api/schedule/calendar
            GET /api/schedule/catalog/users/qa-user
            PUT /api/schedule/catalog/users/qa-user
            GET /api/schedule/catalog/locations
            POST /api/schedule/catalog/locations
            DELETE /api/schedule/catalog/locations/qa-id
            POST /api/schedule/checks/assign
            POST /api/schedule/checks/batch
            POST /api/schedule/checks/leave
            POST /api/schedule/checks/publish
            POST /api/schedule/checks/swap
            POST /api/schedule/official/assignments
            POST /api/schedule/official/assignments/qa-assignment/cancel
            POST /api/schedule/official/assignments/qa-assignment/reassign
            POST /api/schedule/official/preview
            GET /api/schedule/plans
            POST /api/schedule/plans
            GET /api/schedule/plans/compare
            POST /api/schedule/plans/dry-run
            GET /api/schedule/plans/qa-plan
            POST /api/schedule/plans/qa-plan/cancel
            POST /api/schedule/plans/qa-plan/rerun
            POST /api/schedule/plans/qa-plan/approve
            POST /api/schedule/plans/qa-plan/assignments
            POST /api/schedule/plans/qa-plan/assignments/move
            POST /api/schedule/plans/qa-plan/assignments/remove
            POST /api/schedule/plans/qa-plan/exceptions
            POST /api/schedule/plans/qa-plan/exceptions/preview
            POST /api/schedule/plans/qa-plan/publish
            GET /api/schedule/plans/qa-plan/readiness
            POST /api/schedule/plans/qa-plan/reject
            POST /api/schedule/plans/qa-plan/submit
            GET /api/schedule/plans/qa-plan/workflow
            GET /api/schedule/reports/assignments
            GET /api/schedule/reports/hours
            GET /api/schedule/reports/shifts
            GET /api/schedule/reports/versions
            GET /api/schedule/shifts
            POST /api/schedule/shifts
            POST /api/schedule/shifts/bulk
            POST /api/schedule/shifts/emergency
            DELETE /api/schedule/shifts/qa-id
            GET /api/schedule/shifts/qa-id
            PUT /api/schedule/shifts/qa-id
            POST /api/schedule/shifts/qa-id/cancel
            POST /api/schedule/shifts/qa-id/clone
            POST /api/schedule/shifts/qa-id/repeat
            GET /api/workspace/catalog
            GET /api/workspace/employees
            POST /api/workspace/employees
            GET /api/workspace/employees/page
            GET /api/workspace/employees/qa-id
            PUT /api/workspace/employees/qa-id
            POST /api/workspace/employees/qa-id/archive
            POST /api/workspace/levels
            DELETE /api/workspace/levels/qa-id
            PUT /api/workspace/levels/qa-id
            """;

    static final Set<String> PUBLIC = Set.of("GET /", "GET /api/health", "GET /api/health/database",
            "POST /api/auth/login", "POST /api/auth/logout");

    record Endpoint(HttpMethod method, String path) {
        String key() { return method.name() + " " + path; }
        @Override public String toString() { return key(); }
    }

    static List<Endpoint> all() {
        List<Endpoint> list = new ArrayList<>();
        CATALOG.lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(l -> {
            String[] parts = l.split(" ", 2);
            list.add(new Endpoint(HttpMethod.valueOf(parts[0]), parts[1]));
        });
        return list;
    }

    static Stream<Endpoint> protectedEndpoints() {
        return all().stream().filter(e -> !PUBLIC.contains(e.key()));
    }

    static Stream<Endpoint> scheduleNamespace() {
        return all().stream().filter(e -> e.path().startsWith("/api/schedule/"));
    }

    static Stream<Arguments> forgedTokenMatrix() {
        List<Arguments> rows = new ArrayList<>();
        for (String kind : List.of("garbage", "tampered-signature", "role-escalated-payload", "expired", "wrong-secret",
                "alg-none")) {
            protectedEndpoints().forEach(e -> rows.add(Arguments.of(kind, e)));
        }
        return rows.stream();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtUtil jwt;

    @Test
    void catalogCoversEveryMappingInTheCodebase() {
        assertThat(all()).hasSize(153);
        assertThat(all().stream().map(Endpoint::key).distinct()).hasSize(153);
        assertThat(protectedEndpoints()).hasSize(148);
        assertThat(scheduleNamespace()).hasSize(47);
    }

    @ParameterizedTest(name = "[{index}] anonymous {0} -> 401")
    @MethodSource("protectedEndpoints")
    void anonymousCallerIsRejectedWithJson401(Endpoint endpoint) throws Exception {
        mvc.perform(request(endpoint.method(), endpoint.path()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Unauthorized\"}"));
    }

    @ParameterizedTest(name = "[{index}] {0} token on {1} -> 401")
    @MethodSource("forgedTokenMatrix")
    void forgedOrExpiredTokensAreTreatedAsAnonymous(String kind, Endpoint endpoint) throws Exception {
        mvc.perform(request(endpoint.method(), endpoint.path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged(kind)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "[{index}] employee {0} -> 403")
    @MethodSource("scheduleNamespace")
    void employeeTokenCannotEnterAdminScheduleNamespace(Endpoint endpoint) throws Exception {
        mvc.perform(request(endpoint.method(), endpoint.path())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue("qa_user_1", "qa_user_1", "user")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"Forbidden\"}"));
    }

    @ParameterizedTest(name = "[{index}] admin {0} passes security layer")
    @MethodSource("scheduleNamespace")
    void adminTokenPassesSecurityLayerOnScheduleNamespace(Endpoint endpoint) throws Exception {
        try {
            int code = mvc.perform(request(endpoint.method(), endpoint.path())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue("usr_admin_001", "admin", "admin")))
                    .andReturn().getResponse().getStatus();
            assertThat(code).as(endpoint.key()).isNotIn(401, 403);
        } catch (jakarta.servlet.ServletException reachedRepository) {
            // H2 trong profile test không có bảng: request đã qua bảo mật, vào controller và chạm tới câu SQL.
            assertThat(reachedRepository).rootCause().isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void publicEndpointsAnswerWithoutToken() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(request(HttpMethod.POST, "/api/auth/logout")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/workspace.html"));
        mvc.perform(get("/workspace.html")).andExpect(status().isOk());
        mvc.perform(get("/attendance.html")).andExpect(status().isOk());
        mvc.perform(get("/js/workspace.js")).andExpect(status().isOk());
        mvc.perform(get("/css/workspace.css")).andExpect(status().isOk());
    }

    @Test
    void repositoryFilesAndLegacyPagesAreNotPublic() throws Exception {
        for (String path : List.of("/.env", "/pom.xml", "/demo.html", "/partials/login.html", "/README.md",
                "/src/main/resources/application.properties", "/actuator/health", "/actuator/env")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
        String user = jwt.issue("qa_user_1", "qa_user_1", "user");
        for (String path : List.of("/.env", "/pom.xml", "/js/../pom.xml", "/js/%2e%2e/pom.xml")) {
            int code = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + user)).andReturn()
                    .getResponse().getStatus();
            assertThat(code).as("signed-in " + path).isIn(400, 404);
        }
    }

    @Test
    void responsesCarryDefensiveSecurityHeaders() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void corsAllowsOnlyConfiguredOriginsAndMethods() throws Exception {
        mvc.perform(options("/api/periods").header("Origin", "http://localhost:8000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8000"));
        mvc.perform(options("/api/periods").header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        // Ghi nhận cấu hình hiện tại: PUT/DELETE cross-origin bị chặn ở preflight.
        mvc.perform(options("/api/periods/x").header("Origin", "http://localhost:8000")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleClaimIsCaseInsensitiveAndMissingRoleFallsBackToUser() throws Exception {
        String noRole = io.jsonwebtoken.Jwts.builder().subject("qa_user_2").expiration(
                        java.util.Date.from(java.time.Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        mvc.perform(get("/api/schedule/plans").header(HttpHeaders.AUTHORIZATION, "Bearer " + noRole))
                .andExpect(status().isForbidden());
    }

    String forged(String kind) {
        String userToken = jwt.issue("qa_user_1", "qa_user_1", "user");
        String[] parts = userToken.split("\\.");
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return switch (kind) {
            case "garbage" -> "this.is.not-a-jwt";
            case "tampered-signature" -> parts[0] + "." + parts[1] + "."
                    + (parts[2].charAt(0) == 'A' ? 'B' : 'A') + parts[2].substring(1);
            case "role-escalated-payload" -> parts[0] + "." + b64.encodeToString(
                    ("{\"sub\":\"usr_admin_001\",\"username\":\"admin\",\"role\":\"admin\",\"exp\":4102444800}")
                            .getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
            case "expired" -> new JwtUtil(TEST_SECRET, -60_000L).issue("usr_admin_001", "admin", "admin");
            case "wrong-secret" -> new JwtUtil("another-secret-key-of-at-least-32-characters!!", 600_000L)
                    .issue("usr_admin_001", "admin", "admin");
            case "alg-none" -> b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                    + "." + b64.encodeToString(("{\"sub\":\"usr_admin_001\",\"username\":\"admin\",\"role\":\"admin\","
                    + "\"exp\":4102444800}").getBytes(StandardCharsets.UTF_8)) + ".";
            default -> throw new IllegalArgumentException(kind);
        };
    }
}
