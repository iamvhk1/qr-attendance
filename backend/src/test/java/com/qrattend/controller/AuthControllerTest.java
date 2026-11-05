package com.qrattend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.dto.auth.LoginRequest;
import com.qrattend.dto.auth.LoginResponse;
import com.qrattend.dto.auth.OtpResponse;
import com.qrattend.dto.auth.RegisterRequest;
import com.qrattend.dto.auth.RegisterResponse;
import com.qrattend.exception.*;
import com.qrattend.mail.EmailDeliveryException;
import com.qrattend.security.JwtAuthenticationEntryPoint;
import com.qrattend.security.JwtAuthenticationFilter;
import com.qrattend.security.JwtUtil;
import com.qrattend.service.AuthService;
import com.qrattend.service.EmailOtpService;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer tests for {@link AuthController}: routing, validation, and the
 * HTTP status each service exception maps to. Security filters are disabled.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AuthService authService;
    @MockBean private EmailOtpService emailOtpService;

    // Satisfy the security beans that @WebMvcTest picks up
    @MockBean private JwtUtil jwtUtil;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private static final String EMAIL = "prof@cse.iitm.ac.in";

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    // ── POST /api/auth/otp ────────────────────────────────────────

    @Nested
    class RequestOtp {

        @Test
        void validEmail_Returns200WithTimings() throws Exception {
            given(emailOtpService.requestOtp(EMAIL)).willReturn(new OtpResponse(600, 60));

            mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.expiresInSeconds").value(600))
                    .andExpect(jsonPath("$.resendAfterSeconds").value(60));
        }

        @Test
        void disallowedDomain_Returns400() throws Exception {
            given(emailOtpService.requestOtp(anyString()))
                    .willThrow(new EmailNotAllowedException("Only @iitm.ac.in email addresses can register."));

            mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"someone@gmail.com\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Only @iitm.ac.in email addresses can register."));
        }

        @Test
        void alreadyRegistered_Returns409() throws Exception {
            given(emailOtpService.requestOtp(EMAIL)).willThrow(new DuplicateResourceException("exists"));

            mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        void tooSoon_Returns429WithRetryAfter() throws Exception {
            given(emailOtpService.requestOtp(EMAIL))
                    .willThrow(new TooManyRequestsException("Please wait 42 seconds before requesting another code.", 42));

            mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "42"))
                    .andExpect(jsonPath("$.message").value("Please wait 42 seconds before requesting another code."));
        }

        @Test
        void emailProviderDown_Returns503WithFriendlyMessage() throws Exception {
            given(emailOtpService.requestOtp(EMAIL))
                    .willThrow(new EmailDeliveryException("Brevo said 401 unauthorized"));

            mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("try again")))
                    // provider details are not leaked to the client
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("Brevo"))));
        }

        @Test
        void blankOrMalformedEmail_Returns400WithoutCallingService() throws Exception {
            mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"\"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"not-an-email\"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(emailOtpService);
        }
    }

    // ── POST /api/auth/register ───────────────────────────────────

    @Nested
    class Register {

        @Test
        void validRequest_Returns201() throws Exception {
            RegisterRequest request = new RegisterRequest(EMAIL, "123456", "password123", "Name");
            given(authService.register(any(RegisterRequest.class)))
                    .willReturn(new RegisterResponse(UUID.randomUUID(), EMAIL, "Name"));

            mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value(EMAIL))
                    .andExpect(jsonPath("$.fullName").value("Name"));
        }

        @Test
        void wrongCode_Returns400() throws Exception {
            RegisterRequest request = new RegisterRequest(EMAIL, "123456", "password123", "Name");
            given(authService.register(any(RegisterRequest.class)))
                    .willThrow(new InvalidOtpException("Incorrect code. 4 attempts left."));

            mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Incorrect code. 4 attempts left."));
        }

        @Test
        void disallowedDomain_Returns400() throws Exception {
            RegisterRequest request = new RegisterRequest("s@smail.iitm.ac.in", "123456", "password123", "Name");
            given(authService.register(any(RegisterRequest.class)))
                    .willThrow(new EmailNotAllowedException("Student email addresses can't register."));

            mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void duplicateEmail_Returns409() throws Exception {
            RegisterRequest request = new RegisterRequest(EMAIL, "123456", "password123", "Name");
            given(authService.register(any(RegisterRequest.class)))
                    .willThrow(new DuplicateResourceException("Duplicate email"));

            mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Duplicate email"));
        }

        @Test
        void codeNotSixDigits_Returns400WithoutCallingService() throws Exception {
            for (String bad : new String[]{"", "12345", "1234567", "abcdef"}) {
                mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                                .content(json(new RegisterRequest(EMAIL, bad, "password123", "Name"))))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("otp")));
            }
            verifyNoInteractions(authService);
        }

        @Test
        void otherValidationFailures_Return400() throws Exception {
            // Missing email and short password
            mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(json(new RegisterRequest("", "123456", "short", "Name"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").exists());
            verifyNoInteractions(authService);
        }
    }

    // ── POST /api/auth/login ──────────────────────────────────────

    @Test
    void login_ValidRequest_Returns200() throws Exception {
        given(authService.login(any(LoginRequest.class)))
                .willReturn(new LoginResponse("token123", UUID.randomUUID(), "test@test.com", "Name"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("test@test.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token123"));
    }

    @Test
    void login_InvalidCredentials_Returns401() throws Exception {
        given(authService.login(any(LoginRequest.class)))
                .willThrow(new InvalidCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("test@test.com", "password123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Bad credentials"));
    }
}
