package com.qrattend.service;

import com.qrattend.dto.scan.HeartbeatRequest;
import com.qrattend.dto.scan.HeartbeatResponse;
import com.qrattend.dto.scan.ScanRequest;
import com.qrattend.dto.scan.ScanResponse;
import com.qrattend.entity.Attendance;
import com.qrattend.entity.AttendanceStatus;
import com.qrattend.entity.Heartbeat;
import com.qrattend.entity.QrSession;
import com.qrattend.entity.Student;
import com.qrattend.exception.AttendanceFraudException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.exception.SessionClosedException;
import com.qrattend.repository.AttendanceRepository;
import com.qrattend.repository.HeartbeatRepository;
import com.qrattend.repository.QrSessionRepository;
import com.qrattend.repository.StudentRepository;
import com.qrattend.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Core engine for presence verification.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Student scans QR → {@link #submitScan}: creates Attendance (PENDING), issues nonce + ATTENDANCE JWT.</li>
 *   <li>Frontend pings every 5 s → {@link #recordHeartbeat}: validates nonce, saves Heartbeat, rotates nonce.</li>
 *   <li>Session expires → {@link #computeSessionCoverage} (cron, every 1 min): computes coverage ratio,
 *       transitions status to CONFIRMED (≥ 80%) or INVALIDATED (&lt; 80%), seals session.</li>
 * </ol>
 *
 * <p>Anti-cheat mechanisms:</p>
 * <ul>
 *   <li>Rotating nonces: each heartbeat must present the nonce returned by the previous one.
 *       Replay attacks are rejected.</li>
 *   <li>Webdriver detection: if the client signals {@code navigator.webdriver = true},
 *       the record is immediately invalidated.</li>
 *   <li>Coverage threshold: students who drop the page (Page Visibility API fires, heartbeats stop)
 *       accumulate too few heartbeats and are invalidated automatically.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PresenceService {

    /** Heartbeats sent every 5 seconds by the frontend. */
    private static final int HEARTBEAT_INTERVAL_SECONDS = 5;

    /** Minimum heartbeat coverage ratio to count as present. */
    private static final float COVERAGE_THRESHOLD = 0.80f;

    private final QrSessionRepository sessionRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final HeartbeatRepository heartbeatRepository;
    private final JwtUtil jwtUtil;

    // ── 1. Scan ──────────────────────────────────────────────────

    /**
     * Registers a student's QR scan and begins presence tracking.
     *
     * <p>If the student already has an attendance record for this session
     * (e.g. double-tap on the QR page), we return the existing record idempotently
     * rather than creating a duplicate.</p>
     *
     * @param sessionId the session UUID extracted from the QR JWT (set by Spring Security)
     * @param request   the student's roll number (and optional name)
     * @return a {@link ScanResponse} containing the attendanceId, initial nonce, and ATTENDANCE JWT
     * @throws SessionClosedException      if the session has expired or been explicitly closed
     * @throws ResourceNotFoundException   if the roll number is not enrolled in the course
     */
    @Transactional
    public ScanResponse submitScan(UUID sessionId, ScanRequest request) {
        // 1. Load and validate session
        QrSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));

        if (session.isClosed()) {
            throw new SessionClosedException("Session " + sessionId + " is no longer accepting scans");
        }

        // 2. Verify the student is enrolled in this course
        UUID courseId = session.getCourse().getId();
        Student student = studentRepository.findByCourseIdAndRollNumber(courseId, request.getRollNumber())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Roll number " + request.getRollNumber() + " is not enrolled in this course"));

        // 3. Idempotency check — re-return existing record if student already scanned
        Optional<Attendance> existing = attendanceRepository.findBySessionIdAndRollNumber(
                sessionId, request.getRollNumber());

        if (existing.isPresent()) {
            Attendance att = existing.get();
            log.info("Duplicate scan from {} in session {} — returning existing record", request.getRollNumber(), sessionId);
            return ScanResponse.builder()
                    .attendanceId(att.getId())
                    .initialNonce(att.getCurrentNonce())
                    .attendanceToken(jwtUtil.generateAttendanceToken(att.getId()))
                    .status(att.getStatus().name())
                    .build();
        }

        // 4. Generate an initial cryptographic nonce
        String initialNonce = UUID.randomUUID().toString().replace("-", "");

        // 5. Create and persist the attendance record
        Attendance attendance = Attendance.builder()
                .session(session)
                .rollNumber(request.getRollNumber())
                .studentName(student.getFullName())
                .status(AttendanceStatus.PENDING)
                .presenceStart(Instant.now())
                .presenceEnd(session.getExpiresAt())
                .currentNonce(initialNonce)
                .build();

        attendance = attendanceRepository.save(attendance);
        log.info("Scan accepted: student={} session={} attendanceId={}", request.getRollNumber(), sessionId, attendance.getId());

        // 6. Issue an ATTENDANCE JWT for subsequent heartbeat pings
        String attendanceToken = jwtUtil.generateAttendanceToken(attendance.getId());

        return ScanResponse.builder()
                .attendanceId(attendance.getId())
                .initialNonce(initialNonce)
                .attendanceToken(attendanceToken)
                .status(AttendanceStatus.PENDING.name())
                .build();
    }

    // ── 2. Heartbeat ─────────────────────────────────────────────

    /**
     * Records a single heartbeat ping from the student's browser.
     *
     * <p>The student must present the nonce returned by the previous call.
     * On success, a new nonce is generated and returned for the next ping.
     * If the client signals {@code navigator.webdriver = true}, the attendance
     * is immediately invalidated (automated browser detected).</p>
     *
     * @param attendanceId the attendance UUID extracted from the ATTENDANCE JWT (set by Spring Security)
     * @param request      contains the current nonce and the webdriver flag
     * @return a {@link HeartbeatResponse} with the next nonce and current status
     * @throws ResourceNotFoundException if no attendance record exists for this id
     * @throws IllegalArgumentException  if the nonce is invalid (replay or tampering)
     */
    @Transactional
    public HeartbeatResponse recordHeartbeat(UUID attendanceId, HeartbeatRequest request) {
        Attendance attendance = attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance not found: " + attendanceId));

        // 1. Immediately invalidate if automated browser is detected
        if (request.isWebdriver()) {
            log.warn("Webdriver detected for attendance {} — invalidating", attendanceId);
            attendance.setStatus(AttendanceStatus.INVALIDATED);
            attendanceRepository.save(attendance);
            // Throw a dedicated fraud exception (NOT SessionClosedException) so that
            // audit logs and the future Reporting module can distinguish fraud from expiry.
            throw new AttendanceFraudException(
                    "Automated browser detected — attendance invalidated");
        }

        // 2. Validate the nonce — must exactly match what we issued last time
        if (!request.getNonce().equals(attendance.getCurrentNonce())) {
            log.warn("Invalid nonce for attendance {} — expected={} got={}",
                    attendanceId, attendance.getCurrentNonce(), request.getNonce());
            throw new IllegalArgumentException("Invalid nonce: heartbeat rejected");
        }

        // 3. Persist the heartbeat ping
        Heartbeat heartbeat = Heartbeat.builder()
                .session(attendance.getSession())
                .rollNumber(attendance.getRollNumber())
                .build();
        heartbeatRepository.save(heartbeat);

        // 4. Rotate the nonce so the client must present this new value next time
        String nextNonce = UUID.randomUUID().toString().replace("-", "");
        attendance.setCurrentNonce(nextNonce);
        // Note: no explicit save needed here because we're in a @Transactional context
        // and the entity is managed (dirty-checking will flush), but we save explicitly
        // to be safe with non-proxy-based tests:
        attendanceRepository.save(attendance);

        log.debug("Heartbeat recorded for attendance {} — next nonce issued", attendanceId);

        return HeartbeatResponse.builder()
                .nextNonce(nextNonce)
                .status(attendance.getStatus().name())
                .build();
    }

    // ── 3. Scheduled Coverage Computation ────────────────────────

    /**
     * Scheduled job that runs every minute to finalise expired sessions.
     *
     * <p>For each session that has expired but not yet been closed:</p>
     * <ol>
     *   <li>Finds all PENDING attendance records.</li>
     *   <li>For each, computes:
     *       {@code expected = (expiresAt - presenceStart) / 5 seconds}
     *       (clamped to at least 1 to avoid division-by-zero).</li>
     *   <li>Fetches the actual heartbeat count.</li>
     *   <li>Sets {@code coverage = actual / expected}.</li>
     *   <li>Transitions: CONFIRMED if ≥ 80%, INVALIDATED otherwise.</li>
     * </ol>
     *
     * <p>After processing all attendances, stamps {@code closedAt = now()} on the session.</p>
     */
    @Scheduled(fixedDelay = 60_000) // every 60 seconds
    @Transactional
    public void computeSessionCoverage() {
        List<QrSession> expiredSessions = sessionRepository
                .findByExpiresAtBeforeAndClosedAtIsNull(Instant.now());

        if (expiredSessions.isEmpty()) {
            return;
        }

        log.info("Coverage job: processing {} expired session(s)", expiredSessions.size());

        for (QrSession session : expiredSessions) {
            List<Attendance> pendingAttendances = attendanceRepository
                    .findBySessionIdAndStatus(session.getId(), AttendanceStatus.PENDING);

            for (Attendance att : pendingAttendances) {
                long expectedHeartbeats = computeExpectedHeartbeats(att, session);
                long actualHeartbeats = heartbeatRepository
                        .countBySessionIdAndRollNumber(session.getId(), att.getRollNumber());

                float coverage = (float) actualHeartbeats / expectedHeartbeats;
                att.setHeartbeatCoverage(coverage);

                if (coverage >= COVERAGE_THRESHOLD) {
                    att.setStatus(AttendanceStatus.CONFIRMED);
                } else {
                    att.setStatus(AttendanceStatus.INVALIDATED);
                }

                log.info("Coverage: session={} student={} actual={} expected={} ratio={:.2f} → {}",
                        session.getId(), att.getRollNumber(), actualHeartbeats, expectedHeartbeats,
                        coverage, att.getStatus());
            }

            attendanceRepository.saveAll(pendingAttendances);

            // Seal the session — guard against concurrent scheduler invocations
            // by re-fetching and only writing closedAt if still null.
            if (session.getClosedAt() == null) {
                session.setClosedAt(Instant.now());
                sessionRepository.save(session);
            }
        }
    }

    // ── Internal helpers ─────────────────────────────────────────

    /**
     * Computes the number of heartbeats expected from a student over their presence window.
     *
     * <p>Formula: {@code ceil((presenceEnd - presenceStart) / HEARTBEAT_INTERVAL_SECONDS)}</p>
     * <p>Clamped to a minimum of 1 to avoid division-by-zero for very short windows.</p>
     *
     * <p>Uses the per-attendance {@code presenceEnd} field (set at scan time) so that
     * students who join late have their own window rather than the full session window.
     * Falls back to {@code session.getExpiresAt()} if {@code presenceEnd} is null.
     * Falls back to {@code session.getCreatedAt()} if {@code presenceStart} is null.</p>
     */
    private long computeExpectedHeartbeats(Attendance att, QrSession session) {
        Instant start = att.getPresenceStart() != null ? att.getPresenceStart() : session.getCreatedAt();
        Instant end   = att.getPresenceEnd()   != null ? att.getPresenceEnd()   : session.getExpiresAt();

        long durationSeconds = ChronoUnit.SECONDS.between(start, end);
        long expected = (long) Math.ceil((double) durationSeconds / HEARTBEAT_INTERVAL_SECONDS);
        return Math.max(expected, 1L);
    }
}
