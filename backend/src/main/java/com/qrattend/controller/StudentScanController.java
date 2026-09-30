package com.qrattend.controller;

import com.qrattend.dto.scan.DoubtRequest;
import com.qrattend.dto.scan.HeartbeatRequest;
import com.qrattend.dto.scan.HeartbeatResponse;
import com.qrattend.dto.scan.ScanRequest;
import com.qrattend.dto.scan.ScanResponse;
import com.qrattend.entity.Doubt;
import com.qrattend.entity.QrSession;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.repository.AttendanceRepository;
import com.qrattend.repository.DoubtRepository;
import com.qrattend.repository.QrSessionRepository;
import com.qrattend.service.PresenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for student-facing presence verification endpoints.
 *
 * <h2>Authentication</h2>
 * <ul>
 *   <li>{@code POST /api/student/scan} — requires {@code ROLE_SCAN} (15-second QR JWT).
 *       The session UUID is embedded as the JWT subject and extracted by
 *       {@code JwtAuthenticationFilter} into the Spring Security principal.</li>
 *   <li>{@code POST /api/student/heartbeat} — requires {@code ROLE_ATTENDANCE} (2-hour JWT).
 *       The attendance UUID is embedded as the JWT subject and extracted similarly.</li>
 * </ul>
 *
 * <h2>Anti-cheat contract</h2>
 * <p>Each heartbeat must present the nonce returned by the previous response.
 * The server rotates the nonce on every successful heartbeat, so a simple
 * replay script cannot pass the check without holding the live page open.</p>
 */
@RestController
@RequestMapping("/api/student")
@RequiredArgsConstructor
public class StudentScanController {

    private final PresenceService presenceService;
    private final DoubtRepository doubtRepository;
    private final AttendanceRepository attendanceRepository;
    private final QrSessionRepository qrSessionRepository;

    // ── POST /api/student/scan ────────────────────────────────────

    /**
     * Registers the student's QR scan and begins presence tracking.
     *
     * <p>The session UUID is read from the Spring Security principal (populated by
     * {@code JwtAuthenticationFilter} after validating the SCAN JWT from the Authorization header).
     * The request body carries the student's roll number.</p>
     *
     * <p>Returns an {@code ATTENDANCE} JWT that the frontend must store and send as
     * {@code Authorization: Bearer <token>} on every subsequent heartbeat call.</p>
     *
     * @return 200 OK with {@link ScanResponse} (attendanceId, initialNonce, attendanceToken)
     */
    @PostMapping("/scan")
    public ResponseEntity<ScanResponse> scan(@Valid @RequestBody ScanRequest request) {
        UUID sessionId = getSessionId();
        ScanResponse response = presenceService.submitScan(sessionId, request);
        return ResponseEntity.ok(response);
    }

    // ── POST /api/student/heartbeat ───────────────────────────────

    /**
     * Records a single heartbeat ping and rotates the nonce.
     *
     * <p>The attendance UUID is read from the Spring Security principal (populated by
     * {@code JwtAuthenticationFilter} after validating the ATTENDANCE JWT).
     * The request body must contain the nonce returned by the previous scan/heartbeat call.</p>
     *
     * <p>Returns the next nonce that must be sent with the following heartbeat.
     * Responds with {@code 400 Bad Request} if the nonce is wrong or the student
     * is detected as using an automated browser.</p>
     *
     * @return 200 OK with {@link HeartbeatResponse} (nextNonce, currentStatus)
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        UUID attendanceId = getAttendanceId();
        HeartbeatResponse response = presenceService.recordHeartbeat(attendanceId, request);
        return ResponseEntity.ok(response);
    }

    // ── POST /api/student/doubt ───────────────────────────────────

    /**
     * Submits an anonymous doubt question for the current session.
     *
     * <p>Requires the ATTENDANCE JWT, ensuring only students who have successfully scanned
     * and hold a valid attendance token can submit doubts.</p>
     *
     * @return 200 OK with an empty body on success
     */
    @PostMapping("/doubt")
    public ResponseEntity<Void> submitDoubt(@Valid @RequestBody DoubtRequest request) {
        UUID attendanceId = getAttendanceId();
        com.qrattend.entity.Attendance attendance = attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record not found: " + attendanceId));
        QrSession session = attendance.getSession();

        Doubt doubt = Doubt.builder()
                .session(session)
                .question(request.getText())
                .build();
        doubtRepository.save(doubt);

        return ResponseEntity.ok().build();
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Extracts the session UUID from the Spring Security context.
     * Set by {@code JwtAuthenticationFilter} for every valid SCAN token.
     */
    private UUID getSessionId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    /**
     * Extracts the attendance UUID from the Spring Security context.
     * Set by {@code JwtAuthenticationFilter} for every valid ATTENDANCE token.
     */
    private UUID getAttendanceId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
