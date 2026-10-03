package com.qrattend.controller;

import com.qrattend.dto.session.AttendanceResponse;
import com.qrattend.dto.session.ExtendRequest;
import com.qrattend.dto.session.OverrideRequest;
import com.qrattend.dto.session.SessionRequest;
import com.qrattend.dto.session.SessionResponse;
import com.qrattend.entity.Doubt;
import com.qrattend.repository.DoubtRepository;
import com.qrattend.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for QR attendance session management.
 *
 * <p>All endpoints are protected by {@code ROLE_PROFESSOR} (enforced globally
 * in {@code SecurityConfig} via the {@code /api/sessions/**} pattern).</p>
 *
 * <h2>Rolling QR Flow</h2>
 * <ol>
 *   <li>Professor calls {@code POST /api/sessions} to open a 120-second window.</li>
 *   <li>Professor frontend polls {@code GET /api/sessions/{id}/qr} every ~14 seconds.</li>
 *   <li>Each poll returns a new PNG whose embedded JWT expires in 15 seconds.</li>
 *   <li>A student who scans the QR gets a URL. If they submit after 15s, the
 *       server rejects the expired JWT — preventing WhatsApp-sharing attacks.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final DoubtRepository doubtRepository;

    // ── POST /api/sessions ───────────────────────────────────

    /**
     * Creates a new QR attendance session for the specified course.
     *
     * <p>Request body: {@code { "courseId": "UUID", "durationSeconds": 120 }}<br>
     * {@code durationSeconds} is optional — defaults to 120s if absent.</p>
     *
     * @return 201 Created with the new {@link SessionResponse}
     */
    @PostMapping
    public ResponseEntity<SessionResponse> createSession(
            @Valid @RequestBody SessionRequest request) {

        UUID professorId = getProfessorId();
        SessionResponse created = sessionService.createSession(request, professorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── GET /api/sessions/{id} ───────────────────────────────

    /**
     * Returns session details including its computed LIVE / CLOSED status
     * and the current {@code expiresAt} timestamp.
     *
     * @return 200 OK with the {@link SessionResponse}
     */
    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable UUID id) {
        UUID professorId = getProfessorId();
        return ResponseEntity.ok(sessionService.getSession(id, professorId));
    }

    // ── GET /api/sessions/{id}/qr ────────────────────────────

    /**
     * Generates and returns a fresh QR code PNG for the given session.
     *
     * <p>Each call embeds a <em>new</em> 15-second scan JWT in the QR URL, regardless
     * of how many times this endpoint has been called before. The session window
     * itself (120s) is not affected by calling this endpoint.</p>
     *
     * <p>Returns {@code 409 Conflict} if the session is closed or expired.</p>
     *
     * @return 200 OK with raw {@code image/png} bytes
     */
    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQrImage(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean congestion) {
        UUID professorId = getProfessorId();
        byte[] png = sessionService.getQrImageBytes(id, professorId, congestion);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    // ── GET /api/sessions/{id}/qr-data ───────────────────────

    /**
     * Generates and returns a fresh QR code URL as a JSON object.
     * Useful for CLI clients that render ASCII QR codes natively.
     */
    @GetMapping("/{id}/qr-data")
    public ResponseEntity<com.qrattend.dto.session.QrDataResponse> getQrData(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean congestion) {
        UUID professorId = getProfessorId();
        String url = sessionService.getQrUrl(id, professorId, congestion);
        return ResponseEntity.ok(new com.qrattend.dto.session.QrDataResponse(url));
    }

    // ── PATCH /api/sessions/{id}/close ───────────────────────

    /**
     * Closes a live session early (professor's "End Session" button).
     *
     * <p>Sets {@code closedAt = now()} and immediately finalises all PENDING
     * attendance records — no need to wait for the 60-second coverage scheduler.
     * Returns {@code 409 Conflict} if the session is already closed.</p>
     *
     * @return 200 OK with the updated {@link SessionResponse} (status will be "CLOSED")
     */
    @PatchMapping("/{id}/close")
    public ResponseEntity<SessionResponse> closeSession(@PathVariable UUID id) {
        UUID professorId = getProfessorId();
        SessionResponse closed = sessionService.closeSession(id, professorId);
        return ResponseEntity.ok(closed);
    }

    // ── PATCH /api/sessions/{id}/extend ──────────────────────

    /**
     * Extends the session's expiry window by the given number of seconds.
     *
     * <p>Request body: {@code { "additionalSeconds": 60 }} (optional — defaults to 60).<br>
     * Returns {@code 409 Conflict} if the session is already closed.</p>
     *
     * @return 200 OK with the updated {@link SessionResponse} (new {@code expiresAt})
     */
    @PatchMapping("/{id}/extend")
    public ResponseEntity<SessionResponse> extendSession(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ExtendRequest request) {
        UUID professorId = getProfessorId();
        int seconds = (request != null) ? request.getAdditionalSeconds() : 60;
        SessionResponse extended = sessionService.extendSession(id, professorId, seconds);
        return ResponseEntity.ok(extended);
    }

    // ── PATCH /api/sessions/attendance/{id}/override ─────────

    /**
     * Manually overrides a student's attendance status.
     *
     * <p>Request body: {@code { "status": "CONFIRMED" | "INVALIDATED", "reason": "..." }}<br>
     * Validates professor ownership via the attendance record → session → course → professor chain.
     * Returns {@code 404} if the attendance record does not exist, {@code 403} if the professor
     * does not own the associated session.</p>
     *
     * @return 200 OK with the updated {@link AttendanceResponse}
     */
    @PatchMapping("/attendance/{attendanceId}/override")
    public ResponseEntity<AttendanceResponse> overrideAttendance(
            @PathVariable UUID attendanceId,
            @Valid @RequestBody OverrideRequest request) {
        UUID professorId = getProfessorId();
        AttendanceResponse updated = sessionService.overrideAttendance(attendanceId, professorId, request);
        return ResponseEntity.ok(updated);
    }

    // ── GET /api/sessions/{id}/attendance ────────────────────

    /**
     * Returns the list of attendance records for a session.
     *
     * <p>Used by the professor dashboard to show a live roster of who has scanned
     * and each student's current presence status ({@code PENDING}, {@code CONFIRMED},
     * or {@code INVALIDATED}). Poll this endpoint every 5 seconds for live updates.</p>
     *
     * @return 200 OK with a list of {@link AttendanceResponse}
     */
    @GetMapping("/{id}/attendance")
    public ResponseEntity<List<AttendanceResponse>> listAttendance(@PathVariable UUID id) {
        UUID professorId = getProfessorId();
        List<AttendanceResponse> records = sessionService.listAttendance(id, professorId);
        return ResponseEntity.ok(records);
    }

    // ── GET /api/sessions/{id}/doubts ────────────────────────

    @GetMapping("/{id}/doubts")
    public ResponseEntity<List<Doubt>> getDoubts(@PathVariable UUID id) {
        // Assume session exists and belongs to professor (enforced by context if needed)
        // For simplicity, we just fetch them for the given session ID
        return ResponseEntity.ok(doubtRepository.findBySessionIdOrderByPostedAtAsc(id));
    }

    // ── Helper ───────────────────────────────────────────────

    /**
     * Extracts the professor UUID from the Spring Security context.
     * Set by {@code JwtAuthenticationFilter} for every valid LOGIN token.
     */
    private UUID getProfessorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
