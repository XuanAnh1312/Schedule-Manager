package org.dacnpm.schedulemanager.schedule.validation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

@WebMvcTest(SelfCheckController.class)
@Import({JwtUtil.class, SecurityConfig.class})
@ActiveProfiles({"sqlserver", "test"})
class SelfCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private AssignmentCheckService service;

    @Test
    void claim_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/my/checks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shiftId\":\"s1\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).checkAssignment(any());
    }

    @Test
    void claim_checksTheSignedInEmployee_ignoringAnyUserIdInTheBody() throws Exception {
        AssignmentCandidate expected = new AssignmentCandidate("nv001", "s1", null);
        when(service.checkAssignment(expected)).thenReturn(new ValidationResult(List.of()));
        String token = jwtUtil.issue("nv001", "nv001", "user");

        mockMvc.perform(post("/api/my/checks/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shiftId\":\"s1\",\"userId\":\"nv999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        verify(service).checkAssignment(expected);
    }

    @Test
    void claim_withoutShiftId_returns400() throws Exception {
        String token = jwtUtil.issue("nv001", "nv001", "user");

        mockMvc.perform(post("/api/my/checks/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
