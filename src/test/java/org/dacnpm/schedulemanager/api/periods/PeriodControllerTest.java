package org.dacnpm.schedulemanager.api.periods;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.SchedulingSetupExceptionHandler;
import org.dacnpm.schedulemanager.security.JwtUtil;
import org.dacnpm.schedulemanager.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hợp đồng bảo mật /api/periods: không token → 401, token sai/hết hạn → 401, người gọi lấy từ JWT
 * (không từ body), lỗi nghiệp vụ trả JSON {error, code}.
 */
@WebMvcTest(PeriodController.class)
@Import({JwtUtil.class, SecurityConfig.class, SchedulingSetupExceptionHandler.class})
@ActiveProfiles({"sqlserver", "test"})
class PeriodControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private PeriodService service;

    @Test
    void withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/periods")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/periods/p1/reopen")).andExpect(status().isUnauthorized());
    }

    @Test
    void withMalformedOrExpiredToken_returns401() throws Exception {
        mockMvc.perform(get("/api/periods").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
        String expired = new JwtUtil("test-secret-key-at-least-32-chars-long-for-hmac", 0L).issue("nv001", "nv001", "user");
        Thread.sleep(10);
        mockMvc.perform(get("/api/periods").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validToken_callerComesFromJwt() throws Exception {
        when(service.list(any())).thenReturn(List.of());
        mockMvc.perform(get("/api/periods").header(HttpHeaders.AUTHORIZATION, bearer("nv001", "user")))
                .andExpect(status().isOk());
        verify(service).list(new CurrentUser("nv001", false));
    }

    @Test
    void businessError_isJsonWithCode() throws Exception {
        when(service.reopen(eq(new CurrentUser("nv001", false)), eq("p1"), any()))
                .thenThrow(ApiException.forbidden("Bạn không có quyền thực hiện thao tác này."));
        mockMvc.perform(post("/api/periods/p1/reopen")
                        .header(HttpHeaders.AUTHORIZATION, bearer("nv001", "user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true,\"reason\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminToken_mapsToAdminCaller() throws Exception {
        when(service.open(any(), eq("p1"), any())).thenReturn(null);
        mockMvc.perform(post("/api/periods/p1/open").header(HttpHeaders.AUTHORIZATION, bearer("usr_admin_001", "admin")))
                .andExpect(status().isOk());
        verify(service).open(eq(new CurrentUser("usr_admin_001", true)), eq("p1"), any());
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwtUtil.issue(userId, userId, role);
    }
}
