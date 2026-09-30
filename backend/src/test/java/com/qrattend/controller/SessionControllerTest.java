package com.qrattend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.dto.session.SessionRequest;
import com.qrattend.dto.session.SessionResponse;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.GlobalExceptionHandler;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.exception.SessionClosedException;
import com.qrattend.security.JwtAuthenticationEntryPoint;
import com.qrattend.security.JwtAuthenticationFilter;
import com.qrattend.security.JwtUtil;
import com.qrattend.service.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer tests for {@link SessionController}.
 *
 * <p>Security filters are disabled ({@code addFilters = false}). The
 * SecurityContext is populated manually in {@code @BeforeEach} to
 * simulate an authenticated professor.</p>
 *
 * <p>The service layer is mocked — only HTTP routing, validation, serialisation,
 * and exception mapping are verified here.</p>
 */
@WebMvcTest(SessionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("SessionController")
class SessionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private SessionService sessionService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private final UUID profId    = UUID.randomUUID();
    private final UUID courseId  = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                profId, null, List.of(new SimpleGrantedAuthority("ROLE_PROFESSOR")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Fixture builder ──────────────────────────────────────────

    private SessionResponse buildLiveResponse() {
        Instant now = Instant.now();
        return SessionResponse.builder()
                .id(sessionId)
                .courseId(courseId)
                .professorId(profId)
                .createdAt(now)
                .expiresAt(now.plus(120, ChronoUnit.SECONDS))
                .extendedCount(0)
                .closedAt(null)
                .status("LIVE")
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    //  POST /api/sessions
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/sessions")
    class CreateSession {

        @Test
        @DisplayName("Returns 201 Created with SessionResponse body on success")
        void returns201OnSuccess() throws Exception {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(120)
                    .build();
            SessionResponse resp = buildLiveResponse();
            given(sessionService.createSession(any(SessionRequest.class), eq(profId)))
                    .willReturn(resp);

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(sessionId.toString()))
                    .andExpect(jsonPath("$.courseId").value(courseId.toString()))
                    .andExpect(jsonPath("$.status").value("LIVE"))
                    .andExpect(jsonPath("$.extendedCount").value(0));
        }

        @Test
        @DisplayName("Returns 201 Created when durationSeconds is omitted (null → server default)")
        void returns201WhenDurationOmitted() throws Exception {
            // durationSeconds is omitted → session DTO uses server default
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .build();
            given(sessionService.createSession(any(SessionRequest.class), eq(profId)))
                    .willReturn(buildLiveResponse());

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Returns 400 when courseId is missing")
        void returns400WhenCourseIdMissing() throws Exception {
            String json = "{\"durationSeconds\":120}";

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Returns 400 when durationSeconds is below minimum (14 < 15)")
        void returns400WhenDurationBelowMinimum() throws Exception {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(0)   // @Min(1) — zero is below minimum
                    .build();

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Returns 201 when durationSeconds is 1 (new minimum, was 15)")
        void returns201WhenDurationIs1() throws Exception {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(1)   // @Min(1) — exactly at the new floor
                    .build();

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Returns 404 when the course is not found")
        void returns404WhenCourseNotFound() throws Exception {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(120)
                    .build();
            given(sessionService.createSession(any(), eq(profId)))
                    .willThrow(new ResourceNotFoundException("Course not found"));

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 403 when professor does not own the course")
        void returns403WhenNotCourseOwner() throws Exception {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(120)
                    .build();
            given(sessionService.createSession(any(), eq(profId)))
                    .willThrow(new ForbiddenException("You do not own course " + courseId));

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Returns 400 for malformed JSON body")
        void returns400ForMalformedJson() throws Exception {
            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("not-json"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Exactly 15 seconds duration is accepted (boundary value)")
        void accepts15SecondMinimumDuration() throws Exception {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(15)   // minimum allowed
                    .build();
            given(sessionService.createSession(any(), eq(profId)))
                    .willReturn(buildLiveResponse());

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GET /api/sessions/{id}
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/sessions/{id}")
    class GetSession {

        @Test
        @DisplayName("Returns 200 OK with full SessionResponse body")
        void returns200WithSessionDetails() throws Exception {
            given(sessionService.getSession(sessionId, profId))
                    .willReturn(buildLiveResponse());

            mockMvc.perform(get("/api/sessions/" + sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(sessionId.toString()))
                    .andExpect(jsonPath("$.courseId").value(courseId.toString()))
                    .andExpect(jsonPath("$.professorId").value(profId.toString()))
                    .andExpect(jsonPath("$.status").value("LIVE"))
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("Returns 200 with status CLOSED for a closed session")
        void returns200WithClosedStatus() throws Exception {
            Instant now = Instant.now();
            SessionResponse closed = SessionResponse.builder()
                    .id(sessionId)
                    .courseId(courseId)
                    .professorId(profId)
                    .createdAt(now.minus(10, ChronoUnit.MINUTES))
                    .expiresAt(now.minus(5, ChronoUnit.MINUTES))
                    .status("CLOSED")
                    .build();
            given(sessionService.getSession(sessionId, profId)).willReturn(closed);

            mockMvc.perform(get("/api/sessions/" + sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));
        }

        @Test
        @DisplayName("Returns 404 when session does not exist")
        void returns404WhenSessionNotFound() throws Exception {
            given(sessionService.getSession(sessionId, profId))
                    .willThrow(new ResourceNotFoundException("Session not found: " + sessionId));

            mockMvc.perform(get("/api/sessions/" + sessionId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 403 when professor does not own the session")
        void returns403WhenNotOwner() throws Exception {
            given(sessionService.getSession(sessionId, profId))
                    .willThrow(new ForbiddenException("You do not own session " + sessionId));

            mockMvc.perform(get("/api/sessions/" + sessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Returns 400 for an invalid (non-UUID) session ID in path")
        void returns400ForNonUuidId() throws Exception {
            mockMvc.perform(get("/api/sessions/not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GET /api/sessions/{id}/qr
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/sessions/{id}/qr")
    class GetQrImage {

        // Real minimal PNG header (8 bytes), sufficient for Content-Type verification
        private static final byte[] FAKE_PNG =
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        @Test
        @DisplayName("Returns 200 with Content-Type: image/png for a live session")
        void returns200WithImagePngContentType() throws Exception {
            given(sessionService.getQrImageBytes(sessionId, profId, false)).willReturn(FAKE_PNG);

            mockMvc.perform(get("/api/sessions/" + sessionId + "/qr"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG));
        }

        @Test
        @DisplayName("Response body is the raw PNG bytes returned by the service")
        void responseBodyContainsPngBytes() throws Exception {
            given(sessionService.getQrImageBytes(sessionId, profId, false)).willReturn(FAKE_PNG);

            byte[] body = mockMvc.perform(get("/api/sessions/" + sessionId + "/qr"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();

            org.assertj.core.api.Assertions.assertThat(body).isEqualTo(FAKE_PNG);
        }

        @Test
        @DisplayName("Returns 409 Conflict when session is closed or expired")
        void returns409WhenSessionClosed() throws Exception {
            given(sessionService.getQrImageBytes(sessionId, profId, false))
                    .willThrow(new SessionClosedException(
                            "Cannot generate QR: session " + sessionId + " is closed or expired"));

            mockMvc.perform(get("/api/sessions/" + sessionId + "/qr"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Returns 404 when session does not exist")
        void returns404WhenSessionNotFound() throws Exception {
            given(sessionService.getQrImageBytes(sessionId, profId, false))
                    .willThrow(new ResourceNotFoundException("Session not found: " + sessionId));

            mockMvc.perform(get("/api/sessions/" + sessionId + "/qr"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 403 when professor does not own the session")
        void returns403WhenNotOwner() throws Exception {
            given(sessionService.getQrImageBytes(sessionId, profId, false))
                    .willThrow(new ForbiddenException("You do not own session " + sessionId));

            mockMvc.perform(get("/api/sessions/" + sessionId + "/qr"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Returns 400 for a non-UUID path variable")
        void returns400ForNonUuidId() throws Exception {
            mockMvc.perform(get("/api/sessions/not-a-uuid/qr"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Returns 500 when QR generation fails (IOException → RuntimeException)")
        void returns500WhenQrGenerationFails() throws Exception {
            given(sessionService.getQrImageBytes(sessionId, profId, false))
                    .willThrow(new RuntimeException("Failed to generate QR code",
                            new java.io.IOException("disk full")));

            mockMvc.perform(get("/api/sessions/" + sessionId + "/qr"))
                    .andExpect(status().isInternalServerError());
        }
    }
}
