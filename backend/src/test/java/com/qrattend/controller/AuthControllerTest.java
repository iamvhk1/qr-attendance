package com.qrattend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.dto.auth.LoginRequest;
import com.qrattend.dto.auth.LoginResponse;
import com.qrattend.dto.auth.RegisterRequest;
import com.qrattend.dto.auth.RegisterResponse;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.GlobalExceptionHandler;
import com.qrattend.exception.InvalidCredentialsException;
import com.qrattend.exception.InvalidInviteCodeException;
import com.qrattend.security.JwtAuthenticationEntryPoint;
import com.qrattend.security.JwtAuthenticationFilter;
import com.qrattend.security.JwtUtil;
import com.qrattend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer tests for {@link AuthController}.
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
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    // These @MockBean entries satisfy the Spring context dependency graph:
    // JwtAuthenticationFilter (@Component) depends on JwtUtil
    // JwtAuthenticationEntryPoint (@Component) depends on ObjectMapper (auto-configured)
    // Without these, @WebMvcTest fails with "No qualifying bean" errors.
    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    // ── Register ──────────────────────────────────────────────────

    @Test
    void register_ValidRequest_Returns201() throws Exception {
        RegisterRequest request = new RegisterRequest("code", "test@test.com", "password123", "Name");
        RegisterResponse response = new RegisterResponse(UUID.randomUUID(), "test@test.com", "Name");
        
        given(authService.register(any(RegisterRequest.class))).willReturn(response);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("test@test.com"))
                .andExpect(jsonPath("$.fullName").value("Name"));
    }

    @Test
    void register_InvalidInviteCode_Returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("code", "test@test.com", "password123", "Name");
        
        given(authService.register(any(RegisterRequest.class)))
                .willThrow(new InvalidInviteCodeException("Bad code"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bad code"));
    }

    @Test
    void register_DuplicateEmail_Returns409() throws Exception {
        RegisterRequest request = new RegisterRequest("code", "test@test.com", "password123", "Name");
        
        given(authService.register(any(RegisterRequest.class)))
                .willThrow(new DuplicateResourceException("Duplicate email"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Duplicate email"));
    }

    @Test
    void register_ValidationFails_Returns400() throws Exception {
        // Missing email and short password
        RegisterRequest request = new RegisterRequest("code", "", "short", "Name");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // ── Login ─────────────────────────────────────────────────────

    @Test
    void login_ValidRequest_Returns200() throws Exception {
        LoginRequest request = new LoginRequest("test@test.com", "password123");
        LoginResponse response = new LoginResponse("token123", UUID.randomUUID(), "test@test.com", "Name");

        given(authService.login(any(LoginRequest.class))).willReturn(response);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token123"));
    }

    @Test
    void login_InvalidCredentials_Returns401() throws Exception {
        LoginRequest request = new LoginRequest("test@test.com", "password123");

        given(authService.login(any(LoginRequest.class)))
                .willThrow(new InvalidCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Bad credentials"));
    }
}
