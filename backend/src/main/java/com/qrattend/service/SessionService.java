package com.qrattend.service;

import com.google.zxing.WriterException;
import com.qrattend.dto.session.AttendanceResponse;
import com.qrattend.dto.session.OverrideRequest;
import com.qrattend.dto.session.SessionRequest;
import com.qrattend.dto.session.SessionResponse;
import com.qrattend.entity.Attendance;
import com.qrattend.entity.AttendanceStatus;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.entity.QrSession;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.exception.SessionClosedException;
import com.qrattend.repository.AttendanceRepository;
import com.qrattend.repository.CourseRepository;
import com.qrattend.repository.HeartbeatRepository;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.repository.QrSessionRepository;
import com.qrattend.security.JwtUtil;
import com.qrattend.util.QrGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for QR attendance session management.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Create a session with a configurable window (default 120s)</li>
 *   <li>Generate a rolling 15-second QR code PNG on demand</li>
 *   <li>Return session details (including computed LIVE / CLOSED status)</li>
 * </ul>
 *
 * <p>The rolling QR mechanism works as follows:
 * <ol>
 *   <li>The professor's frontend polls {@code GET /api/sessions/{id}/qr} every ~14 seconds.</li>
 *   <li>Each call generates a <em>fresh</em> 15-second scan JWT via {@link JwtUtil#generateScanToken}.</li>
 *   <li>That JWT is embedded in the scan URL, which is then encoded as a QR PNG.</li>
 *   <li>The session entity in the DB is <em>not</em> modified on each QR fetch — only the JWT changes.</li>
 *   <li>A student who scans the QR gets the URL. If they submit after 15s, the JWT is expired and
 *       the server rejects the scan — preventing WhatsApp sharing attacks.</li>
 * </ol>
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final QrSessionRepository sessionRepository;
    private final CourseRepository courseRepository;
    private final ProfessorRepository professorRepository;
    private final AttendanceRepository attendanceRepository;
    private final HeartbeatRepository heartbeatRepository;
    private final JwtUtil jwtUtil;

    @Value("${app.session.default-duration-seconds}")
    private int defaultDurationSeconds;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /** Width and height of the generated QR code PNG in pixels. */
    private static final int QR_SIZE_PX = 300;

    // ── Create ──────────────────────────────────────────────

    /**
     * Creates a new QR attendance session for the given course.
     *
     * <p>The session window is set to {@code now + durationSeconds}. If
     * {@code durationSeconds} is null, the server default (120s) is used.</p>
     *
     * @param request     contains courseId and optional durationSeconds
     * @param professorId the authenticated professor's UUID (from JWT)
     * @return the created session as a response DTO
     * @throws ResourceNotFoundException if the course does not exist
     * @throws ForbiddenException        if the professor does not own the course
     */
    @Transactional
    public SessionResponse createSession(SessionRequest request, UUID professorId) {
        Course course = getOwnedCourse(request.getCourseId(), professorId);
        Professor professor = professorRepository.findById(professorId)
                .orElseThrow(() -> new ResourceNotFoundException("Professor not found"));

        int duration = (request.getDurationSeconds() != null)
                ? request.getDurationSeconds()
                : defaultDurationSeconds;

        Instant expiresAt = Instant.now().plus(duration, ChronoUnit.SECONDS);

        QrSession session = QrSession.builder()
                .course(course)
                .professor(professor)
                .expiresAt(expiresAt)
                .build();

        QrSession saved = sessionRepository.save(session);
        log.info("Session created: {} for course {} by professor {} (window: {}s)",
                saved.getId(), course.getId(), professorId, duration);

        return SessionResponse.fromEntity(saved);
    }

    // ── Read ─────────────────────────────────────────────────

    /**
     * Returns the details of a single session, including its computed LIVE / CLOSED status.
     *
     * @param sessionId   the session UUID
     * @param professorId the authenticated professor's UUID
     * @return the session as a response DTO
     * @throws ResourceNotFoundException if the session does not exist
     * @throws ForbiddenException        if the professor does not own the session
     */
    @Transactional(readOnly = true)
    public SessionResponse getSession(UUID sessionId, UUID professorId) {
        QrSession session = getOwnedSession(sessionId, professorId);
        return SessionResponse.fromEntity(session);
    }

    // ── QR Image ─────────────────────────────────────────────

    /**
     * Generates a fresh QR code PNG for the given session.
     *
     * <p>This is the core of the rolling QR mechanism. Every call:</p>
     * <ol>
     *   <li>Verifies the session is still live (not closed, not expired)</li>
     *   <li>Generates a <em>new</em> 15-second scan JWT (subject = sessionId, type = SCAN)</li>
     *   <li>Embeds the JWT in a scan URL: {@code {frontendUrl}/scan?token={jwt}}</li>
     *   <li>Encodes that URL as a 300×300 QR code PNG</li>
     * </ol>
     *
     * <p>The professor frontend polls this endpoint every ~14 seconds so that
     * the displayed QR always contains a fresh, unexpired token.</p>
     *
     * @param sessionId   the session UUID
     * @param professorId the authenticated professor's UUID
     * @return raw PNG bytes ready to send as {@code image/png}
     * @throws SessionClosedException    if the session is already closed or expired
     * @throws ResourceNotFoundException if the session does not exist
     * @throws ForbiddenException        if the professor does not own the session
     */
    @Transactional(readOnly = true)
    public byte[] getQrImageBytes(UUID sessionId, UUID professorId, boolean congestionMode) {
        String scanUrl = getQrUrl(sessionId, professorId, congestionMode);
        try {
            byte[] png = QrGenerator.generatePng(scanUrl, QR_SIZE_PX, QR_SIZE_PX);
            log.debug("QR PNG generated for session {}", sessionId);
            return png;
        } catch (WriterException | IOException e) {
            log.error("QR generation failed for session {}: {}", sessionId, e.getMessage());
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }

    /**
     * Generates a fresh scan URL for the CLI to use natively.
     */
    @Transactional(readOnly = true)
    public String getQrUrl(UUID sessionId, UUID professorId, boolean congestionMode) {
        QrSession session = getOwnedSession(sessionId, professorId);

        if (session.isClosed()) {
            throw new SessionClosedException(
                    "Cannot generate QR URL: session " + sessionId + " is closed or expired");
        }

        String scanToken = jwtUtil.generateScanToken(sessionId, congestionMode);
        String scanUrl = frontendUrl + "/scan?token=" + scanToken;
        log.debug("QR URL generated for session {} — congestionMode={}", sessionId, congestionMode);
        
        return scanUrl;
    }

    // ── Close ─────────────────────────────────────────────────

    /**
     * Closes a live session early on the professor's request.
     *
     * <p>This is the professor's "End Session" button. It sets {@code closedAt = now()}
     * and immediately finalises all PENDING attendance records using the same
     * coverage computation as the scheduled job — so students don't have to wait
     * up to 60 seconds for results after the professor manually ends the session.</p>
     *
     * @param sessionId   the session UUID to close
     * @param professorId the authenticated professor's UUID
     * @return the updated session as a response DTO
     * @throws SessionClosedException    if the session is already closed
     * @throws ResourceNotFoundException if the session does not exist
     * @throws ForbiddenException        if the professor does not own the session
     */
    @Transactional
    public SessionResponse closeSession(UUID sessionId, UUID professorId) {
        QrSession session = getOwnedSession(sessionId, professorId);

        if (session.isClosed()) {
            throw new SessionClosedException("Session " + sessionId + " is already closed");
        }

        // Set closedAt FIRST so computeExpectedHeartbeats can use it
        session.setClosedAt(Instant.now());

        // Immediately finalise all PENDING attendance records.
        // Mirrors the scheduled coverage job so results are instant.
        List<Attendance> pending = attendanceRepository
                .findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING);

        for (Attendance att : pending) {
            long expectedHeartbeats = computeExpectedHeartbeats(att, session);
            long actualHeartbeats = heartbeatRepository
                    .countBySessionIdAndRollNumber(sessionId, att.getRollNumber());

            float coverage = Math.min(1.0f, (float) actualHeartbeats / expectedHeartbeats);
            att.setHeartbeatCoverage(coverage);
            att.setStatus(coverage >= COVERAGE_THRESHOLD
                    ? AttendanceStatus.CONFIRMED
                    : AttendanceStatus.INVALIDATED);
        }

        if (!pending.isEmpty()) {
            attendanceRepository.saveAll(pending);
        }

        QrSession saved = sessionRepository.save(session);

        log.info("Session {} manually closed by professor {} — finalised {} attendance record(s)",
                sessionId, professorId, pending.size());

        return SessionResponse.fromEntity(saved);
    }

    // ── Extend ─────────────────────────────────────────────────

    /**
     * Extends a live session's expiry by the given number of seconds.
     *
     * <p>Increments {@code extendedCount} to track how many times the session was extended.
     * Returns {@code 409 Conflict} if the session is already closed.</p>
     *
     * @param sessionId         the session UUID to extend
     * @param professorId       the authenticated professor's UUID
     * @param additionalSeconds seconds to add to the current {@code expiresAt}
     * @return the updated session as a response DTO
     */
    @Transactional
    public SessionResponse extendSession(UUID sessionId, UUID professorId, int additionalSeconds) {
        QrSession session = getOwnedSession(sessionId, professorId);

        if (session.isClosed()) {
            throw new SessionClosedException("Session " + sessionId + " is already closed — cannot extend");
        }

        session.setExpiresAt(session.getExpiresAt().plus(additionalSeconds, ChronoUnit.SECONDS));
        session.setExtendedCount(session.getExtendedCount() + 1);
        QrSession saved = sessionRepository.save(session);

        // Also push out the presenceEnd boundary for any students who already scanned in,
        // otherwise they will accumulate more pings than expected, yielding > 100% coverage.
        List<Attendance> pending = attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING);
        for (Attendance att : pending) {
            if (att.getPresenceEnd() != null) {
                att.setPresenceEnd(att.getPresenceEnd().plus(additionalSeconds, ChronoUnit.SECONDS));
            }
        }
        if (!pending.isEmpty()) {
            attendanceRepository.saveAll(pending);
        }

        log.info("Session {} extended by {}s by professor {} (total extensions: {})",
                sessionId, additionalSeconds, professorId, saved.getExtendedCount());

        return SessionResponse.fromEntity(saved);
    }

    // ── Attendance list ───────────────────────────────────────


    /**
     * Returns all attendance records for a session, ordered by scan time.
     *
     * <p>Used by the professor dashboard to show who has scanned and their
     * current presence status in real time (via polling).</p>
     *
     * @param sessionId   the session UUID
     * @param professorId the authenticated professor's UUID
     * @return list of {@link AttendanceResponse} DTOs
     * @throws ResourceNotFoundException if the session does not exist
     * @throws ForbiddenException        if the professor does not own the session
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> listAttendance(UUID sessionId, UUID professorId) {
        getOwnedSession(sessionId, professorId);

        return attendanceRepository.findBySessionId(sessionId)
                .stream()
                .map(AttendanceResponse::fromEntity)
                .toList();
    }

    // ── Manual Override ───────────────────────────────────────

    /**
     * Manually overrides an attendance record's status.
     *
     * <p>The professor can set any student's status to {@code CONFIRMED} or
     * {@code INVALIDATED} with an optional reason. This is used to correct
     * false negatives/positives caused by device issues or network problems.</p>
     *
     * <p>Ownership is validated by tracing {@code Attendance → Session → Course → Professor}.</p>
     *
     * @param attendanceId the UUID of the attendance record to override
     * @param professorId  the authenticated professor's UUID
     * @param request      contains the new status and an optional override reason
     * @return the updated {@link AttendanceResponse}
     */
    @Transactional
    public AttendanceResponse overrideAttendance(UUID attendanceId, UUID professorId, OverrideRequest request) {
        Attendance attendance = attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record not found: " + attendanceId));

        // Ownership check via session → course → professor
        QrSession session = attendance.getSession();
        if (!session.getCourse().getProfessor().getId().equals(professorId)) {
            throw new ForbiddenException("You do not own the session this attendance belongs to");
        }

        AttendanceStatus newStatus = AttendanceStatus.valueOf(request.getStatus());
        attendance.setStatus(newStatus);
        attendance.setManuallyAdded(true);
        attendance.setOverrideReason(request.getReason());

        Attendance saved = attendanceRepository.save(attendance);
        log.info("Attendance {} manually overridden to {} by professor {} — reason: {}",
                attendanceId, newStatus, professorId, request.getReason());

        return AttendanceResponse.fromEntity(saved);
    }

    // ── Internal helpers ─────────────────────────────────────

    /**
     * Fetches a course by ID and verifies that the professor owns it.
     * Mirrors the identical helper in {@link CourseService} to avoid a cross-service dependency.
     */
    private Course getOwnedCourse(UUID courseId, UUID professorId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course not found: " + courseId));

        if (!course.getProfessor().getId().equals(professorId)) {
            throw new ForbiddenException("You do not own course " + courseId);
        }
        return course;
    }

    /**
     * Fetches a session by ID and verifies that the logged-in professor owns it
     * (by checking ownership of the linked course).
     */
    private QrSession getOwnedSession(UUID sessionId, UUID professorId) {
        QrSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));

        if (!session.getCourse().getProfessor().getId().equals(professorId)) {
            throw new ForbiddenException("You do not own session " + sessionId);
        }
        return session;
    }

    /**
     * Computes the number of heartbeats expected from a student over their presence window.
     * Duplicated from {@link PresenceService} to avoid a cross-service dependency.
     *
     * <p>Formula: {@code ceil((presenceEnd - presenceStart) / 5s)}, clamped to ≥ 1.</p>
     */
    private long computeExpectedHeartbeats(Attendance att, QrSession session) {
        Instant start = att.getPresenceStart() != null ? att.getPresenceStart() : session.getCreatedAt();
        Instant originalEnd = att.getPresenceEnd() != null ? att.getPresenceEnd() : session.getExpiresAt();
        
        // If the session was closed early, the presence window is truncated
        Instant end = (session.getClosedAt() != null && session.getClosedAt().isBefore(originalEnd))
                ? session.getClosedAt()
                : originalEnd;

        long durationSeconds = ChronoUnit.SECONDS.between(start, end);
        long expected = (long) Math.ceil((double) durationSeconds / HEARTBEAT_INTERVAL_SECONDS);
        return Math.max(expected, 1L);
    }

    /** Heartbeat interval must match {@link PresenceService#HEARTBEAT_INTERVAL_SECONDS}. */
    private static final int HEARTBEAT_INTERVAL_SECONDS = 5;

    /** Coverage threshold must match {@link PresenceService#COVERAGE_THRESHOLD}. */
    private static final float COVERAGE_THRESHOLD = 0.80f;
}
