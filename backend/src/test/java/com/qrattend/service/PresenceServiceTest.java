package com.qrattend.service;

import com.qrattend.dto.scan.HeartbeatRequest;
import com.qrattend.dto.scan.HeartbeatResponse;
import com.qrattend.dto.scan.ScanRequest;
import com.qrattend.dto.scan.ScanResponse;
import com.qrattend.entity.Attendance;
import com.qrattend.entity.AttendanceStatus;
import com.qrattend.entity.Course;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceService")
class PresenceServiceTest {

    @Mock private QrSessionRepository sessionRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private HeartbeatRepository heartbeatRepository;
    @Mock private JwtUtil jwtUtil;

    @InjectMocks private PresenceService presenceService;

    private UUID sessionId;
    private UUID courseId;
    private UUID attendanceId;
    private String rollNumber;
    private QrSession liveSession;
    private QrSession closedSession;
    private Student student;
    private Attendance attendance;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID();
        courseId = UUID.randomUUID();
        attendanceId = UUID.randomUUID();
        rollNumber = "CS24B001";

        Course course = Course.builder().id(courseId).build();

        liveSession = QrSession.builder()
                .id(sessionId)
                .course(course)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .build();

        closedSession = QrSession.builder()
                .id(sessionId)
                .course(course)
                .expiresAt(Instant.now().minus(2, ChronoUnit.MINUTES))
                .build();

        student = Student.builder()
                .id(UUID.randomUUID())
                .course(course)
                .rollNumber(rollNumber)
                .fullName("Alice")
                .build();

        attendance = Attendance.builder()
                .id(attendanceId)
                .session(liveSession)
                .rollNumber(rollNumber)
                .status(AttendanceStatus.PENDING)
                .currentNonce("mock-nonce-123")
                .presenceStart(Instant.now().minus(30, ChronoUnit.SECONDS))
                .build();
    }

    // ══════════════════════════════════════════════════════════
    //  submitScan
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("submitScan")
    class SubmitScan {

        @Test
        @DisplayName("Successfully registers scan and returns token and nonce")
        void success() {
            ScanRequest req = new ScanRequest(rollNumber, null);

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(liveSession));
            when(studentRepository.findByCourseIdAndRollNumber(courseId, rollNumber)).thenReturn(Optional.of(student));
            when(attendanceRepository.findBySessionIdAndRollNumber(sessionId, rollNumber)).thenReturn(Optional.empty());
            when(jwtUtil.generateAttendanceToken(any(UUID.class))).thenReturn("mock-attendance-jwt");

            when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> {
                Attendance a = inv.getArgument(0);
                ReflectionTestUtils.setField(a, "id", attendanceId);
                return a;
            });

            ScanResponse response = presenceService.submitScan(sessionId, req);

            assertThat(response).isNotNull();
            assertThat(response.getAttendanceId()).isEqualTo(attendanceId);
            assertThat(response.getAttendanceToken()).isEqualTo("mock-attendance-jwt");
            assertThat(response.getInitialNonce()).isNotBlank();
            assertThat(response.getStatus()).isEqualTo(AttendanceStatus.PENDING.name());

            // Verify the saved entity has all required fields populated
            ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
            verify(attendanceRepository).save(captor.capture());
            Attendance saved = captor.getValue();
            assertThat(saved.getRollNumber()).isEqualTo(rollNumber);
            assertThat(saved.getStatus()).isEqualTo(AttendanceStatus.PENDING);
            assertThat(saved.getPresenceStart()).isNotNull();
            assertThat(saved.getCurrentNonce()).isEqualTo(response.getInitialNonce());
        }

        @Test
        @DisplayName("Returns existing attendance idempotently if student already scanned")
        void returnsExistingIfAlreadyScanned() {
            ScanRequest req = new ScanRequest(rollNumber, null);

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(liveSession));
            when(studentRepository.findByCourseIdAndRollNumber(courseId, rollNumber)).thenReturn(Optional.of(student));
            when(attendanceRepository.findBySessionIdAndRollNumber(sessionId, rollNumber)).thenReturn(Optional.of(attendance));
            when(jwtUtil.generateAttendanceToken(attendanceId)).thenReturn("mock-attendance-jwt");

            ScanResponse response = presenceService.submitScan(sessionId, req);

            assertThat(response.getAttendanceId()).isEqualTo(attendanceId);
            assertThat(response.getInitialNonce()).isEqualTo("mock-nonce-123"); // From existing attendance
            // Must NOT create a new record
            verify(attendanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws SessionClosedException if session is expired/closed")
        void throwsIfSessionClosed() {
            ScanRequest req = new ScanRequest(rollNumber, null);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(closedSession));

            assertThatThrownBy(() -> presenceService.submitScan(sessionId, req))
                    .isInstanceOf(SessionClosedException.class);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException if session does not exist")
        void throwsIfSessionNotFound() {
            ScanRequest req = new ScanRequest(rollNumber, null);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> presenceService.submitScan(sessionId, req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Session not found");
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException if student not enrolled in course")
        void throwsIfStudentNotEnrolled() {
            ScanRequest req = new ScanRequest(rollNumber, null);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(liveSession));
            when(studentRepository.findByCourseIdAndRollNumber(courseId, rollNumber)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> presenceService.submitScan(sessionId, req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not enrolled");
        }
    }

    // ══════════════════════════════════════════════════════════
    //  recordHeartbeat
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("recordHeartbeat")
    class RecordHeartbeat {

        @Test
        @DisplayName("Successfully records heartbeat, rotates nonce, and returns next nonce")
        void success() {
            HeartbeatRequest req = new HeartbeatRequest("mock-nonce-123", false);
            when(attendanceRepository.findById(attendanceId)).thenReturn(Optional.of(attendance));
            when(heartbeatRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            HeartbeatResponse response = presenceService.recordHeartbeat(attendanceId, req);

            assertThat(response).isNotNull();
            assertThat(response.getNextNonce()).isNotBlank().isNotEqualTo("mock-nonce-123");
            assertThat(response.getStatus()).isEqualTo(AttendanceStatus.PENDING.name());

            verify(heartbeatRepository).save(any(Heartbeat.class));
            // Nonce must be rotated on the entity
            assertThat(attendance.getCurrentNonce()).isEqualTo(response.getNextNonce());
        }

        @Test
        @DisplayName("Throws IllegalArgumentException if nonce is incorrect (replay/tampering)")
        void failsIfNonceIncorrect() {
            HeartbeatRequest req = new HeartbeatRequest("wrong-nonce", false);
            when(attendanceRepository.findById(attendanceId)).thenReturn(Optional.of(attendance));

            assertThatThrownBy(() -> presenceService.recordHeartbeat(attendanceId, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid nonce");

            // Nonce must NOT be changed on a failed attempt
            assertThat(attendance.getCurrentNonce()).isEqualTo("mock-nonce-123");
            verify(heartbeatRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws AttendanceFraudException and marks INVALIDATED if webdriver is true")
        void invalidatesAndThrowsIfWebdriver() {
            HeartbeatRequest req = new HeartbeatRequest("mock-nonce-123", true);
            when(attendanceRepository.findById(attendanceId)).thenReturn(Optional.of(attendance));

            // Webdriver detection throws a dedicated fraud exception (NOT SessionClosedException)
            assertThatThrownBy(() -> presenceService.recordHeartbeat(attendanceId, req))
                    .isInstanceOf(AttendanceFraudException.class)
                    .hasMessageContaining("Automated browser detected");

            // Status must be persisted as INVALIDATED before the throw
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.INVALIDATED);
            verify(attendanceRepository).save(attendance);
            // No heartbeat must be recorded
            verify(heartbeatRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException if attendance record does not exist")
        void throwsIfAttendanceNotFound() {
            HeartbeatRequest req = new HeartbeatRequest("any-nonce", false);
            when(attendanceRepository.findById(attendanceId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> presenceService.recordHeartbeat(attendanceId, req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Attendance not found");
        }
    }

    // ══════════════════════════════════════════════════════════
    //  computeSessionCoverage
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("computeSessionCoverage")
    class ComputeSessionCoverage {

        @Test
        @DisplayName("No expired sessions → exits early, no repository writes")
        void noExpiredSessions_doesNothing() {
            when(sessionRepository.findByExpiresAtBeforeAndClosedAtIsNull(any(Instant.class)))
                    .thenReturn(List.of());

            presenceService.computeSessionCoverage();

            verifyNoInteractions(attendanceRepository, heartbeatRepository);
            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Correctly marks CONFIRMED (≥ 80%) and INVALIDATED (< 80%)")
        void correctlyComputesConfirmedAndInvalidated() {
            QrSession expiredUnclosed = QrSession.builder()
                    .id(sessionId)
                    .expiresAt(Instant.now().minus(10, ChronoUnit.SECONDS))
                    .closedAt(null)
                    .build();

            when(sessionRepository.findByExpiresAtBeforeAndClosedAtIsNull(any(Instant.class)))
                    .thenReturn(List.of(expiredUnclosed));

            // student1: window = 60 s → expected = 12; actual = 10 → coverage = 83% → CONFIRMED
            Attendance student1 = Attendance.builder()
                    .id(UUID.randomUUID())
                    .session(expiredUnclosed)
                    .rollNumber("CS24B001")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(expiredUnclosed.getExpiresAt().minus(60, ChronoUnit.SECONDS))
                    .presenceEnd(expiredUnclosed.getExpiresAt())
                    .build();

            // student2: window = 100 s → expected = 20; actual = 10 → coverage = 50% → INVALIDATED
            Attendance student2 = Attendance.builder()
                    .id(UUID.randomUUID())
                    .session(expiredUnclosed)
                    .rollNumber("CS24B002")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(expiredUnclosed.getExpiresAt().minus(100, ChronoUnit.SECONDS))
                    .presenceEnd(expiredUnclosed.getExpiresAt())
                    .build();

            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of(student1, student2));
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B001")).thenReturn(10L);
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B002")).thenReturn(10L);

            presenceService.computeSessionCoverage();

            assertThat(student1.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
            assertThat(student1.getHeartbeatCoverage())
                    .isCloseTo(0.83f, org.assertj.core.api.Assertions.within(0.01f));

            assertThat(student2.getStatus()).isEqualTo(AttendanceStatus.INVALIDATED);
            assertThat(student2.getHeartbeatCoverage())
                    .isCloseTo(0.50f, org.assertj.core.api.Assertions.within(0.01f));

            assertThat(expiredUnclosed.getClosedAt()).isNotNull();
            verify(attendanceRepository, times(1)).saveAll(List.of(student1, student2));
            verify(sessionRepository, times(1)).save(expiredUnclosed);
        }

        @Test
        @DisplayName("Exactly 80% coverage → CONFIRMED (boundary condition)")
        void exactlyEightyPercent_isConfirmed() {
            QrSession expiredUnclosed = QrSession.builder()
                    .id(sessionId)
                    .expiresAt(Instant.now().minus(5, ChronoUnit.SECONDS))
                    .closedAt(null)
                    .build();

            when(sessionRepository.findByExpiresAtBeforeAndClosedAtIsNull(any(Instant.class)))
                    .thenReturn(List.of(expiredUnclosed));

            // window = 50 s → expected = 10; actual = 8 → coverage = exactly 80% → CONFIRMED
            Attendance student = Attendance.builder()
                    .id(UUID.randomUUID())
                    .session(expiredUnclosed)
                    .rollNumber("CS24B001")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(expiredUnclosed.getExpiresAt().minus(50, ChronoUnit.SECONDS))
                    .presenceEnd(expiredUnclosed.getExpiresAt())
                    .build();

            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of(student));
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B001")).thenReturn(8L);

            presenceService.computeSessionCoverage();

            assertThat(student.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
            assertThat(student.getHeartbeatCoverage())
                    .isCloseTo(0.80f, org.assertj.core.api.Assertions.within(0.001f));
        }

        @Test
        @DisplayName("79% coverage → INVALIDATED (just below boundary)")
        void justBelowEightyPercent_isInvalidated() {
            QrSession expiredUnclosed = QrSession.builder()
                    .id(sessionId)
                    .expiresAt(Instant.now().minus(5, ChronoUnit.SECONDS))
                    .closedAt(null)
                    .build();

            when(sessionRepository.findByExpiresAtBeforeAndClosedAtIsNull(any(Instant.class)))
                    .thenReturn(List.of(expiredUnclosed));

            // window = 50 s → expected = 10; actual = 7 → coverage = 70% → INVALIDATED
            Attendance student = Attendance.builder()
                    .id(UUID.randomUUID())
                    .session(expiredUnclosed)
                    .rollNumber("CS24B001")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(expiredUnclosed.getExpiresAt().minus(50, ChronoUnit.SECONDS))
                    .presenceEnd(expiredUnclosed.getExpiresAt())
                    .build();

            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of(student));
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B001")).thenReturn(7L);

            presenceService.computeSessionCoverage();

            assertThat(student.getStatus()).isEqualTo(AttendanceStatus.INVALIDATED);
        }

        @Test
        @DisplayName("Null presenceStart falls back to session.createdAt for expected count")
        void nullPresenceStart_fallsBackToSessionCreatedAt() {
            Instant sessionCreatedAt = Instant.now().minus(60, ChronoUnit.SECONDS);
            Instant sessionExpiresAt = Instant.now().minus(5, ChronoUnit.SECONDS);

            QrSession expiredUnclosed = QrSession.builder()
                    .id(sessionId)
                    .createdAt(sessionCreatedAt)
                    .expiresAt(sessionExpiresAt)
                    .closedAt(null)
                    .build();

            when(sessionRepository.findByExpiresAtBeforeAndClosedAtIsNull(any(Instant.class)))
                    .thenReturn(List.of(expiredUnclosed));

            // presenceStart = null → fallback to sessionCreatedAt → window = 55 s → expected = ceil(55/5) = 11
            // actual = 11 → coverage = 100% → CONFIRMED
            Attendance student = Attendance.builder()
                    .id(UUID.randomUUID())
                    .session(expiredUnclosed)
                    .rollNumber("CS24B001")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(null) // intentionally null
                    .presenceEnd(sessionExpiresAt)
                    .build();

            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of(student));
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B001")).thenReturn(11L);

            presenceService.computeSessionCoverage();

            // Falls back to createdAt → window used is ~55 s → expected = 11 → coverage = 100%
            assertThat(student.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
        }

        @Test
        @DisplayName("Session already closed (closedAt != null) → NOT re-sealed")
        void alreadyClosedSession_notResealed() {
            Instant alreadyClosed = Instant.now().minus(30, ChronoUnit.SECONDS);

            QrSession alreadyClosedSession = QrSession.builder()
                    .id(sessionId)
                    .expiresAt(Instant.now().minus(90, ChronoUnit.SECONDS))
                    .closedAt(alreadyClosed)  // already sealed
                    .build();

            when(sessionRepository.findByExpiresAtBeforeAndClosedAtIsNull(any(Instant.class)))
                    .thenReturn(List.of(alreadyClosedSession));
            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of());

            presenceService.computeSessionCoverage();

            // closedAt was already set — should not be overwritten
            assertThat(alreadyClosedSession.getClosedAt()).isEqualTo(alreadyClosed);
            verify(sessionRepository, never()).save(any());
        }
    }
}
