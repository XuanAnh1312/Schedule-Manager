package org.dacnpm.schedulemanager.api.levels;

import org.dacnpm.schedulemanager.security.JwtUtil;
import org.dacnpm.schedulemanager.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security contract tests for GET /api/levels.
 *
 * <ul>
 *   <li>No token → 401 Unauthorized</li>
 *   <li>Valid JWT → 200 OK with data</li>
 *   <li>Malformed / tampered token → 401 Unauthorized</li>
 *   <li>Expired token → 401 Unauthorized</li>
 * </ul>
 */
@WebMvcTest(LevelController.class)
@Import({JwtUtil.class, SecurityConfig.class})
// Activate "sqlserver" so @Profile("sqlserver") beans (LevelController, LevelRepository)
// are included in the context. Activate "test" so application-test.properties overrides
// the SQL Server datasource and JWT secret — no real DB or .env needed.
@ActiveProfiles({"sqlserver", "test"})
class LevelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private LevelRepository levelRepository;

    // ── Security Contract ─────────────────────────────────────────────────────

    @Test
    void getLevels_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/levels"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getLevels_withValidJwt_returns200() throws Exception {
        stubFourLevels();

        String token = jwtUtil.issue("usr_001", "testuser", "user");

        mockMvc.perform(get("/api/levels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void getLevels_withAdminJwt_returns200() throws Exception {
        stubFourLevels();

        String token = jwtUtil.issue("usr_admin_001", "admin", "admin");

        mockMvc.perform(get("/api/levels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void getLevels_withMalformedToken_returns401() throws Exception {
        mockMvc.perform(get("/api/levels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer this.is.not.a.valid.jwt"))
                .andExpect(status().isUnauthorized());
    }

@Test
void getLevels_withTamperedSignature_returns401() throws Exception {
    String token = jwtUtil.issue("usr_001", "testuser", "user");

    // Tách JWT thành 3 phần: header, payload và signature.
    String[] parts = token.split("\\.");

    // Thay ký tự đầu tiên của signature để chắc chắn chữ ký bị thay đổi.
    String signature = parts[2];
    char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';

    String tamperedSignature = replacement + signature.substring(1);

    // Ghép lại JWT có chữ ký không hợp lệ.
    String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;

    mockMvc.perform(get("/api/levels")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
            .andExpect(status().isUnauthorized());
}

    @Test
    void getLevels_withExpiredToken_returns401() throws Exception {
        // Build a JwtUtil with 0 ms expiry to get an instantly-expired token.
        JwtUtil shortLivedJwtUtil = new JwtUtil(
                "test-secret-key-at-least-32-chars-long-for-hmac", 0L);
        String expiredToken = shortLivedJwtUtil.issue("usr_001", "testuser", "user");

        // Give JJWT a moment to ensure the token is actually expired.
        Thread.sleep(10);

        mockMvc.perform(get("/api/levels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    // ── Response Contract ─────────────────────────────────────────────────────

    @Test
    void getLevels_returnsListWithCorrectFields() throws Exception {
        stubFourLevels();
        String token = jwtUtil.issue("usr_001", "testuser", "user");

        mockMvc.perform(get("/api/levels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value("lvl1"))
                .andExpect(jsonPath("$[0].name").value("Level 1"))
                .andExpect(jsonPath("$[0].description").value("Nhân viên mới"))
                .andExpect(jsonPath("$[0].color").value("#7c3aed"))
                .andExpect(jsonPath("$[0].order").value(1))
                .andExpect(jsonPath("$[3].id").value("lvl4"))
                .andExpect(jsonPath("$[3].name").value("Level 4"))
                .andExpect(jsonPath("$[3].order").value(4));
    }

    @Test
    void getLevels_isSortedByOrder() throws Exception {
        stubFourLevels();
        String token = jwtUtil.issue("usr_001", "testuser", "user");

        mockMvc.perform(get("/api/levels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].order").value(1))
                .andExpect(jsonPath("$[1].order").value(2))
                .andExpect(jsonPath("$[2].order").value(3))
                .andExpect(jsonPath("$[3].order").value(4));
    }

    @Test
    void getLevels_whenEmpty_returnsEmptyArray() throws Exception {
        when(levelRepository.findAll()).thenReturn(List.of());
        String token = jwtUtil.issue("usr_001", "testuser", "user");

        mockMvc.perform(get("/api/levels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubFourLevels() {
        when(levelRepository.findAll()).thenReturn(List.of(
                new LevelResponse("lvl1", "Level 1", "Nhân viên mới",       "#7c3aed", 1),
                new LevelResponse("lvl2", "Level 2", "Nhân viên chính thức", "#be185d", 2),
                new LevelResponse("lvl3", "Level 3", "Nhân viên kỳ cựu",    "#c2410c", 3),
                new LevelResponse("lvl4", "Level 4", "Trưởng ca",            "#065f46", 4)
        ));
    }
}
