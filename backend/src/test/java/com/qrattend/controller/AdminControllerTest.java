package com.qrattend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.dto.admin.InviteRequest;
import com.qrattend.dto.admin.InviteResponse;
import com.qrattend.exception.GlobalExceptionHandler;
import com.qrattend.exception.InvalidCredentialsException;
import com.qrattend.security.JwtAuthenticationEntryPoint;
import com.qrattend.security.JwtAuthenticationFilter;
import com.qrattend.security.JwtUtil;
import com.qrattend.service.AdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer tests for {@link AdminController}.
 *
 * <p>Security filters are disabled ({@code addFilters = false}) so we test
 * only the controller + exception-handler logic, not JWT authentication.
 * JWT/security behavior is tested in {@code JwtAuthenticationFilterTest}.</p>
 *
 * <p>The {@code @MockBean} annotations for security beans are needed because
 * {@code @WebMvcTest} component-scans the application package, which picks up
 * {@code @Component}-annotated security classes. We mock them to satisfy the
 * dependency graph without loading the real security infrastructure.</p>
 */
@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminService adminService;

    // These @MockBean entries satisfy the Spring context dependency graph.
    // See AuthControllerTest javadoc for full explanation.
    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Test
    void generateInvite_ValidSecret_Returns200() throws Exception {
        InviteRequest request = new InviteRequest("correct-secret");
        InviteResponse response = new InviteResponse("token123");

        given(adminService.generateInvite("correct-secret")).willReturn(response);

        mockMvc.perform(post("/api/admin/invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").value("token123"));
    }

    @Test
    void generateInvite_InvalidSecret_Returns401() throws Exception {
        InviteRequest request = new InviteRequest("wrong-secret");

        given(adminService.generateInvite(anyString()))
                .willThrow(new InvalidCredentialsException("Invalid admin secret"));

        mockMvc.perform(post("/api/admin/invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid admin secret"));
    }

    @Test
    void generateInvite_ValidationFails_Returns400() throws Exception {
        InviteRequest request = new InviteRequest(""); // Blank secret

        mockMvc.perform(post("/api/admin/invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
