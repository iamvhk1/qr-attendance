package com.qrattend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.dto.scan.HeartbeatRequest;
import com.qrattend.dto.scan.HeartbeatResponse;
import com.qrattend.dto.scan.ScanRequest;
import com.qrattend.dto.scan.ScanResponse;
import com.qrattend.entity.AttendanceStatus;
import com.qrattend.service.PresenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StudentScanController.class)
@AutoConfigureMockMvc(addFilters = false) // Disable security filters for pure controller testing
@DisplayName("StudentScanController")
class StudentScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PresenceService presenceService;

    @MockBean
    private com.qrattend.security.JwtUtil jwtUtil;

    /** Clear SecurityContextHolder after each test to avoid cross-test pollution. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Helper: set a UUID principal in the SecurityContext ───────────

    private void setSecurityContextPrincipal(UUID principal, String role) {
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── POST /api/student/scan ───────────────────────────────────────

    @Test
    @DisplayName("POST /api/student/scan - 200 OK and verifies sessionId passed to service")
    void submitScan_Success() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID attendanceId = UUID.randomUUID();
        ScanRequest request = new ScanRequest("CS24B001", "Alice");

        setSecurityContextPrincipal(sessionId, "SCAN");

        ScanResponse mockResponse = ScanResponse.builder()
                .attendanceId(attendanceId)
                .initialNonce("initial-nonce")
                .attendanceToken("jwt-token")
                .status(AttendanceStatus.PENDING.name())
                .build();

        when(presenceService.submitScan(eq(sessionId), any(ScanRequest.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/api/student/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceId").value(attendanceId.toString()))
                .andExpect(jsonPath("$.initialNonce").value("initial-nonce"))
                .andExpect(jsonPath("$.attendanceToken").value("jwt-token"))
                .andExpect(jsonPath("$.status").value(AttendanceStatus.PENDING.name()));

        // Verify the exact sessionId from the security principal was passed to the service
        verify(presenceService).submitScan(eq(sessionId), any(ScanRequest.class));
    }

    @Test
    @DisplayName("POST /api/student/scan - 400 when rollNumber is blank")
    @WithMockUser(roles = "SCAN")
    void submitScan_InvalidRequest_BlankRollNumber() throws Exception {
        ScanRequest request = new ScanRequest("", "Alice");

        mockMvc.perform(post("/api/student/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/student/scan - 400 when rollNumber is null")
    @WithMockUser(roles = "SCAN")
    void submitScan_InvalidRequest_NullRollNumber() throws Exception {
        ScanRequest request = new ScanRequest(null, "Alice");

        mockMvc.perform(post("/api/student/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/student/heartbeat ──────────────────────────────────

    @Test
    @DisplayName("POST /api/student/heartbeat - 200 OK and verifies attendanceId passed to service")
    void recordHeartbeat_Success() throws Exception {
        UUID attendanceId = UUID.randomUUID();
        HeartbeatRequest request = new HeartbeatRequest("current-nonce", false);

        setSecurityContextPrincipal(attendanceId, "ATTENDANCE");

        HeartbeatResponse mockResponse = HeartbeatResponse.builder()
                .nextNonce("next-nonce")
                .status(AttendanceStatus.PENDING.name())
                .build();

        when(presenceService.recordHeartbeat(eq(attendanceId), any(HeartbeatRequest.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/api/student/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextNonce").value("next-nonce"))
                .andExpect(jsonPath("$.status").value(AttendanceStatus.PENDING.name()));

        // Verify the exact attendanceId from the security principal was passed to the service
        verify(presenceService).recordHeartbeat(eq(attendanceId), any(HeartbeatRequest.class));
    }

    @Test
    @DisplayName("POST /api/student/heartbeat - 400 when nonce is blank")
    @WithMockUser(roles = "ATTENDANCE")
    void recordHeartbeat_InvalidRequest_BlankNonce() throws Exception {
        HeartbeatRequest request = new HeartbeatRequest("", false);

        mockMvc.perform(post("/api/student/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/student/heartbeat - 400 when nonce is null")
    @WithMockUser(roles = "ATTENDANCE")
    void recordHeartbeat_InvalidRequest_NullNonce() throws Exception {
        HeartbeatRequest request = new HeartbeatRequest(null, false);

        mockMvc.perform(post("/api/student/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
