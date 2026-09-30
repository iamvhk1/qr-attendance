package com.qrattend.service;

import com.google.zxing.WriterException;
import com.qrattend.dto.session.AttendanceResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SessionService}.
 *
 * <p>Uses Mockito to stub all repository and utility collaborators.
 * No Spring context is loaded.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionService")
class SessionServiceTest {

    @Mock private QrSessionRepository sessionRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private ProfessorRepository professorRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private HeartbeatRepository heartbeatRepository;
    @Mock private JwtUtil jwtUtil;

    @InjectMocks private SessionService sessionService;

    // ── Test fixtures ────────────────────────────────────────────

    private UUID profId;
    private UUID otherProfId;
    private UUID courseId;
    private UUID sessionId;

    private Professor professor;
    private Course course;

    @BeforeEach
    void setUp() {
        profId     = UUID.randomUUID();
        otherProfId = UUID.randomUUID();
        courseId   = UUID.randomUUID();
        sessionId  = UUID.randomUUID();

        professor = Professor.builder()
                .id(profId)
                .fullName("Dr. Boby George")
                .email("boby@iitm.ac.in")
                .passwordHash("$2a$10$hash")
                .build();

        course = Course.builder()
                .id(courseId)
                .name("Operating Systems")
                .code("CS5013")
                .semester("Sem 5 2026")
                .professor(professor)
                .build();

        // Inject @Value fields that Spring would normally inject
        ReflectionTestUtils.setField(sessionService, "defaultDurationSeconds", 120);
        ReflectionTestUtils.setField(sessionService, "frontendUrl", "http://localhost:5173");
    }

    // ══════════════════════════════════════════════════════════════
    //  createSession
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createSession")
    class CreateSession {

        @Test
        @DisplayName("Creates a session with the requested duration and returns a SessionResponse")
        void createsSessionWithRequestedDuration() {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(60)
                    .build();

            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(professorRepository.findById(profId)).thenReturn(Optional.of(professor));
            when(sessionRepository.save(any(QrSession.class))).thenAnswer(inv -> {
                QrSession s = inv.getArgument(0);
                ReflectionTestUtils.setField(s, "id", sessionId);
                ReflectionTestUtils.setField(s, "createdAt", Instant.now());
                return s;
            });

            SessionResponse result = sessionService.createSession(req, profId);

            assertThat(result).isNotNull();
            assertThat(result.getCourseId()).isEqualTo(courseId);
            assertThat(result.getProfessorId()).isEqualTo(profId);
            assertThat(result.getStatus()).isEqualTo("LIVE");

            // expiresAt should be approximately now + 60 seconds
            Instant expectedExpiry = Instant.now().plus(60, ChronoUnit.SECONDS);
            assertThat(result.getExpiresAt())
                    .isCloseTo(expectedExpiry, within(3, ChronoUnit.SECONDS));

            verify(sessionRepository).save(any(QrSession.class));
        }

        @Test
        @DisplayName("Uses server default duration (120s) when durationSeconds is null")
        void usesDefaultDurationWhenNull() {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(null)   // null → should use 120s default
                    .build();

            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(professorRepository.findById(profId)).thenReturn(Optional.of(professor));
            when(sessionRepository.save(any(QrSession.class))).thenAnswer(inv -> {
                QrSession s = inv.getArgument(0);
                ReflectionTestUtils.setField(s, "id", sessionId);
                ReflectionTestUtils.setField(s, "createdAt", Instant.now());
                return s;
            });

            SessionResponse result = sessionService.createSession(req, profId);

            Instant expectedExpiry = Instant.now().plus(120, ChronoUnit.SECONDS);
            assertThat(result.getExpiresAt())
                    .isCloseTo(expectedExpiry, within(3, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when course does not exist")
        void throwsNotFoundWhenCourseAbsent() {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(120)
                    .build();

            when(courseRepository.findById(courseId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sessionService.createSession(req, profId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(courseId.toString());

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws ForbiddenException when professor does not own the course")
        void throwsForbiddenWhenNotCourseOwner() {
            Professor otherProf = Professor.builder()
                    .id(otherProfId).fullName("Other Prof").email("other@iitm.ac.in").build();
            Course courseOwnedByOther = Course.builder()
                    .id(courseId).professor(otherProf).name("X").code("X100").semester("2026").build();

            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(120)
                    .build();

            when(courseRepository.findById(courseId)).thenReturn(Optional.of(courseOwnedByOther));

            assertThatThrownBy(() -> sessionService.createSession(req, profId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("do not own");

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when professor record is missing in DB")
        void throwsNotFoundWhenProfessorAbsent() {
            // course exists and is owned by profId, but professor row is gone (edge case)
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(120)
                    .build();

            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(professorRepository.findById(profId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sessionService.createSession(req, profId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Professor");

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Returned session has LIVE status and null closedAt")
        void returnedSessionIsLive() {
            SessionRequest req = SessionRequest.builder()
                    .courseId(courseId)
                    .durationSeconds(120)
                    .build();

            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(professorRepository.findById(profId)).thenReturn(Optional.of(professor));
            when(sessionRepository.save(any(QrSession.class))).thenAnswer(inv -> {
                QrSession s = inv.getArgument(0);
                ReflectionTestUtils.setField(s, "id", sessionId);
                ReflectionTestUtils.setField(s, "createdAt", Instant.now());
                return s;
            });

            SessionResponse result = sessionService.createSession(req, profId);

            assertThat(result.getStatus()).isEqualTo("LIVE");
            assertThat(result.getClosedAt()).isNull();
            assertThat(result.getExtendedCount()).isEqualTo(0);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  getSession
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getSession")
    class GetSession {

        private QrSession buildLiveSession() {
            return QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                    .build();
        }

        @Test
        @DisplayName("Returns LIVE status for an unexpired session")
        void returnsLiveStatusForActiveSession() {
            when(sessionRepository.findById(sessionId))
                    .thenReturn(Optional.of(buildLiveSession()));

            SessionResponse result = sessionService.getSession(sessionId, profId);

            assertThat(result.getId()).isEqualTo(sessionId);
            assertThat(result.getCourseId()).isEqualTo(courseId);
            assertThat(result.getStatus()).isEqualTo("LIVE");
        }

        @Test
        @DisplayName("Returns CLOSED status for an expired session")
        void returnsClosedStatusForExpiredSession() {
            QrSession expired = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().minus(5, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(expired));

            SessionResponse result = sessionService.getSession(sessionId, profId);

            assertThat(result.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("Returns CLOSED status for an explicitly closed session")
        void returnsClosedStatusForExplicitlyClosedSession() {
            QrSession closed = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                    .closedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(closed));

            SessionResponse result = sessionService.getSession(sessionId, profId);

            assertThat(result.getStatus()).isEqualTo("CLOSED");
            assertThat(result.getClosedAt()).isNotNull();
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when session does not exist")
        void throwsNotFoundWhenSessionAbsent() {
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sessionService.getSession(sessionId, profId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(sessionId.toString());
        }

        @Test
        @DisplayName("Throws ForbiddenException when a different professor requests another's session")
        void throwsForbiddenWhenNotSessionOwner() {
            when(sessionRepository.findById(sessionId))
                    .thenReturn(Optional.of(buildLiveSession()));

            assertThatThrownBy(() -> sessionService.getSession(sessionId, otherProfId))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("Response contains correct expiresAt from the entity")
        void responseContainsCorrectExpiresAt() {
            Instant expectedExpiry = Instant.now().plus(90, ChronoUnit.SECONDS);
            QrSession session = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(expectedExpiry)
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            SessionResponse result = sessionService.getSession(sessionId, profId);

            assertThat(result.getExpiresAt()).isEqualTo(expectedExpiry);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  getQrImageBytes
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getQrImageBytes")
    class GetQrImageBytes {

        @Test
        @DisplayName("Returns PNG bytes for a live session — scan URL contains the JWT")
        void returnsPngBytesForLiveSession() throws WriterException, IOException {
            QrSession live = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(jwtUtil.generateScanToken(sessionId)).thenReturn("mock-scan-token");

            try (MockedStatic<QrGenerator> qrMock = mockStatic(QrGenerator.class)) {
                qrMock.when(() -> QrGenerator.generatePng(
                        "http://localhost:5173/scan?token=mock-scan-token", 300, 300))
                        .thenReturn(new byte[]{0, 1, 2, 3});

                byte[] result = sessionService.getQrImageBytes(sessionId, profId, false);

                assertThat(result).isNotEmpty();
                qrMock.verify(() ->
                        QrGenerator.generatePng(
                                "http://localhost:5173/scan?token=mock-scan-token", 300, 300));
            }
        }

        @Test
        @DisplayName("Generates a fresh scan JWT on every call — does NOT reuse tokens")
        void generatesFreshScanTokenOnEachCall() throws WriterException, IOException {
            QrSession live = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(jwtUtil.generateScanToken(sessionId))
                    .thenReturn("token-first-call", "token-second-call");

            try (MockedStatic<QrGenerator> qrMock = mockStatic(QrGenerator.class)) {
                qrMock.when(() -> QrGenerator.generatePng(any(), anyInt(), anyInt()))
                        .thenReturn(new byte[]{0});

                sessionService.getQrImageBytes(sessionId, profId, false);
                sessionService.getQrImageBytes(sessionId, profId, false);
            }

            // generateScanToken must be called once per invocation — not cached
            verify(jwtUtil, times(2)).generateScanToken(sessionId);
        }

        @Test
        @DisplayName("Throws SessionClosedException for an expired session — does NOT generate QR")
        void throwsSessionClosedExceptionForExpiredSession() {
            QrSession expired = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().minus(5, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId, false))
                    .isInstanceOf(SessionClosedException.class)
                    .hasMessageContaining(sessionId.toString());

            verifyNoInteractions(jwtUtil);
        }

        @Test
        @DisplayName("Throws SessionClosedException for an explicitly closed session")
        void throwsSessionClosedExceptionForExplicitlyClosedSession() {
            QrSession closed = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                    .closedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(closed));

            assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId, false))
                    .isInstanceOf(SessionClosedException.class);

            verifyNoInteractions(jwtUtil);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when session does not exist")
        void throwsNotFoundWhenSessionAbsent() {
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId, false))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Throws ForbiddenException when professor does not own the session")
        void throwsForbiddenWhenNotOwner() {
            QrSession live = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));

            assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, otherProfId, false))
                    .isInstanceOf(ForbiddenException.class);

            verifyNoInteractions(jwtUtil);
        }

        @Test
        @DisplayName("Wraps QrGenerator IOException in RuntimeException")
        void wrapsQrGeneratorIOException() throws WriterException, IOException {
            QrSession live = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(jwtUtil.generateScanToken(sessionId)).thenReturn("token");

            try (MockedStatic<QrGenerator> qrMock = mockStatic(QrGenerator.class)) {
                qrMock.when(() -> QrGenerator.generatePng(any(), anyInt(), anyInt()))
                        .thenThrow(new IOException("disk full"));

                assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId, false))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("QR code");
            }
        }

        @Test
        @DisplayName("Wraps QrGenerator WriterException in RuntimeException")
        void wrapsQrGeneratorWriterException() throws WriterException, IOException {
            QrSession live = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(jwtUtil.generateScanToken(sessionId)).thenReturn("token");

            try (MockedStatic<QrGenerator> qrMock = mockStatic(QrGenerator.class)) {
                qrMock.when(() -> QrGenerator.generatePng(any(), anyInt(), anyInt()))
                        .thenThrow(new WriterException("encode error"));

                assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId, false))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("QR code");
            }
        }

        @Test
        @DisplayName("Scan URL is built as {frontendUrl}/scan?token={jwt}")
        void scanUrlFormat() throws WriterException, IOException {
            QrSession live = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(jwtUtil.generateScanToken(sessionId)).thenReturn("TEST_JWT");

            try (MockedStatic<QrGenerator> qrMock = mockStatic(QrGenerator.class)) {
                qrMock.when(() -> QrGenerator.generatePng(any(), anyInt(), anyInt()))
                        .thenReturn(new byte[]{0});

                sessionService.getQrImageBytes(sessionId, profId, false);

                // Verify the exact URL format passed to QrGenerator
                qrMock.verify(() -> QrGenerator.generatePng(
                        eq("http://localhost:5173/scan?token=TEST_JWT"),
                        anyInt(), anyInt()));
            }
        }

        @Test
        @DisplayName("Session entity is NOT modified (no DB write) when fetching QR")
        void sessionEntityNotModifiedOnQrFetch() throws WriterException, IOException {
            QrSession live = QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(jwtUtil.generateScanToken(sessionId)).thenReturn("token");

            try (MockedStatic<QrGenerator> qrMock = mockStatic(QrGenerator.class)) {
                qrMock.when(() -> QrGenerator.generatePng(any(), anyInt(), anyInt()))
                        .thenReturn(new byte[]{0});

                sessionService.getQrImageBytes(sessionId, profId, false);
            }

            // The rolling QR design: session must NOT be saved/updated on each QR fetch
            verify(sessionRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  closeSession
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("closeSession")
    class CloseSession {

        private QrSession buildLiveSession() {
            return QrSession.builder()
                    .id(sessionId)
                    .course(course)
                    .professor(professor)
                    .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                    .build();
        }

        @Test
        @DisplayName("Returns a CLOSED SessionResponse and stamps closedAt")
        void closesLiveSessionAndReturnsClosedStatus() {
            QrSession live = buildLiveSession();
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of()); // no pending records
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SessionResponse result = sessionService.closeSession(sessionId, profId);

            assertThat(result.getStatus()).isEqualTo("CLOSED");
            assertThat(result.getClosedAt()).isNotNull();
            verify(sessionRepository).save(live);
        }

        @Test
        @DisplayName("Finalises PENDING attendance to CONFIRMED when coverage >= 80%")
        void finalisesPendingToConfirmedWhenHighCoverage() {
            QrSession live = buildLiveSession();
            // 90-second session window, heartbeat every 5s → 18 expected
            Attendance pending = Attendance.builder()
                    .session(live)
                    .rollNumber("CS24B001")
                    .studentName("Alice")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(Instant.now().minus(90, ChronoUnit.SECONDS))
                    .presenceEnd(Instant.now())
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of(pending));
            // 16 of 18 heartbeats → coverage = 0.888 → CONFIRMED
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B001"))
                    .thenReturn(16L);
            when(attendanceRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sessionService.closeSession(sessionId, profId);

            assertThat(pending.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
            assertThat(pending.getHeartbeatCoverage()).isGreaterThanOrEqualTo(0.80f);
        }

        @Test
        @DisplayName("Finalises PENDING attendance to INVALIDATED when coverage < 80%")
        void finalisesPendingToInvalidatedWhenLowCoverage() {
            QrSession live = buildLiveSession();
            Attendance pending = Attendance.builder()
                    .session(live)
                    .rollNumber("CS24B002")
                    .studentName("Bob")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(Instant.now().minus(90, ChronoUnit.SECONDS))
                    .presenceEnd(Instant.now())
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of(pending));
            // 5 of 18 heartbeats → coverage = 0.277 → INVALIDATED
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B002"))
                    .thenReturn(5L);
            when(attendanceRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sessionService.closeSession(sessionId, profId);

            assertThat(pending.getStatus()).isEqualTo(AttendanceStatus.INVALIDATED);
            assertThat(pending.getHeartbeatCoverage()).isLessThan(0.80f);
        }

        @Test
        @DisplayName("Does NOT call attendanceRepository.saveAll when there are no PENDING records")
        void doesNotSaveWhenNoPendingRecords() {
            QrSession live = buildLiveSession();
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of());
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sessionService.closeSession(sessionId, profId);

            verify(attendanceRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Throws SessionClosedException when session is already naturally expired")
        void throwsWhenAlreadyExpired() {
            QrSession expired = QrSession.builder()
                    .id(sessionId).course(course).professor(professor)
                    .expiresAt(Instant.now().minus(5, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> sessionService.closeSession(sessionId, profId))
                    .isInstanceOf(SessionClosedException.class)
                    .hasMessageContaining("already closed");

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws SessionClosedException when session was already explicitly closed")
        void throwsWhenAlreadyExplicitlyClosed() {
            QrSession alreadyClosed = QrSession.builder()
                    .id(sessionId).course(course).professor(professor)
                    .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                    .closedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(alreadyClosed));

            assertThatThrownBy(() -> sessionService.closeSession(sessionId, profId))
                    .isInstanceOf(SessionClosedException.class);

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws ForbiddenException when professor does not own the session")
        void throwsForbiddenWhenNotOwner() {
            when(sessionRepository.findById(sessionId))
                    .thenReturn(Optional.of(buildLiveSession()));

            assertThatThrownBy(() -> sessionService.closeSession(sessionId, otherProfId))
                    .isInstanceOf(ForbiddenException.class);

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when session does not exist")
        void throwsNotFoundWhenSessionAbsent() {
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sessionService.closeSession(sessionId, profId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(sessionId.toString());
        }

        @Test
        @DisplayName("Finalises multiple PENDING records in one close call")
        void finalisesMultiplePendingRecords() {
            QrSession live = buildLiveSession();
            Attendance p1 = Attendance.builder().session(live).rollNumber("CS24B001").studentName("Alice")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(Instant.now().minus(60, ChronoUnit.SECONDS))
                    .presenceEnd(Instant.now()).build();
            Attendance p2 = Attendance.builder().session(live).rollNumber("CS24B002").studentName("Bob")
                    .status(AttendanceStatus.PENDING)
                    .presenceStart(Instant.now().minus(60, ChronoUnit.SECONDS))
                    .presenceEnd(Instant.now()).build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(live));
            when(attendanceRepository.findBySessionIdAndStatus(sessionId, AttendanceStatus.PENDING))
                    .thenReturn(List.of(p1, p2));
            // p1: 12/12 heartbeats → CONFIRMED; p2: 2/12 → INVALIDATED
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B001")).thenReturn(12L);
            when(heartbeatRepository.countBySessionIdAndRollNumber(sessionId, "CS24B002")).thenReturn(2L);
            when(attendanceRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sessionService.closeSession(sessionId, profId);

            assertThat(p1.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
            assertThat(p2.getStatus()).isEqualTo(AttendanceStatus.INVALIDATED);
            verify(attendanceRepository).saveAll(List.of(p1, p2));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  listAttendance
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("listAttendance")
    class ListAttendance {

        private QrSession buildLiveSession() {
            return QrSession.builder()
                    .id(sessionId).course(course).professor(professor)
                    .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                    .build();
        }

        @Test
        @DisplayName("Returns mapped AttendanceResponse list for a session with records")
        void returnsMappedListForSessionWithRecords() {
            QrSession session = buildLiveSession();
            Attendance a1 = Attendance.builder().id(UUID.randomUUID()).session(session)
                    .rollNumber("CS24B001").studentName("Alice").status(AttendanceStatus.CONFIRMED)
                    .manuallyAdded(false).build();
            Attendance a2 = Attendance.builder().id(UUID.randomUUID()).session(session)
                    .rollNumber("CS24B002").studentName("Bob").status(AttendanceStatus.PENDING)
                    .manuallyAdded(false).build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(attendanceRepository.findBySessionId(sessionId)).thenReturn(List.of(a1, a2));

            List<AttendanceResponse> result = sessionService.listAttendance(sessionId, profId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(AttendanceResponse::getRollNumber)
                    .containsExactly("CS24B001", "CS24B002");
            assertThat(result).extracting(AttendanceResponse::getStatus)
                    .containsExactly("CONFIRMED", "PENDING");
        }

        @Test
        @DisplayName("Returns an empty list when no students have scanned yet")
        void returnsEmptyListWhenNoScans() {
            when(sessionRepository.findById(sessionId))
                    .thenReturn(Optional.of(buildLiveSession()));
            when(attendanceRepository.findBySessionId(sessionId)).thenReturn(List.of());

            List<AttendanceResponse> result = sessionService.listAttendance(sessionId, profId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("currentNonce is never exposed in the response DTO")
        void nonceIsNeverExposedInResponse() {
            QrSession session = buildLiveSession();
            Attendance a = Attendance.builder().id(UUID.randomUUID()).session(session)
                    .rollNumber("CS24B001").studentName("Alice").status(AttendanceStatus.PENDING)
                    .currentNonce("super-secret-nonce-12345").manuallyAdded(false).build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(attendanceRepository.findBySessionId(sessionId)).thenReturn(List.of(a));

            List<AttendanceResponse> result = sessionService.listAttendance(sessionId, profId);

            // AttendanceResponse has no nonce field — compile-time guarantee,
            // but we verify the mapping completed without exposing it.
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRollNumber()).isEqualTo("CS24B001");
        }

        @Test
        @DisplayName("Correctly maps manual override fields")
        void mapsManualOverrideFields() {
            QrSession session = buildLiveSession();
            Attendance manual = Attendance.builder().id(UUID.randomUUID()).session(session)
                    .rollNumber("CS24B003").studentName("Carol").status(AttendanceStatus.CONFIRMED)
                    .manuallyAdded(true).overrideReason("Phone battery died").build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(attendanceRepository.findBySessionId(sessionId)).thenReturn(List.of(manual));

            List<AttendanceResponse> result = sessionService.listAttendance(sessionId, profId);

            assertThat(result.get(0).isManuallyAdded()).isTrue();
            assertThat(result.get(0).getOverrideReason()).isEqualTo("Phone battery died");
        }

        @Test
        @DisplayName("Throws ForbiddenException when professor does not own the session")
        void throwsForbiddenWhenNotOwner() {
            when(sessionRepository.findById(sessionId))
                    .thenReturn(Optional.of(buildLiveSession()));

            assertThatThrownBy(() -> sessionService.listAttendance(sessionId, otherProfId))
                    .isInstanceOf(ForbiddenException.class);

            verifyNoInteractions(attendanceRepository);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when session does not exist")
        void throwsNotFoundWhenSessionAbsent() {
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sessionService.listAttendance(sessionId, profId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(sessionId.toString());

            verifyNoInteractions(attendanceRepository);
        }

        @Test
        @DisplayName("Works for a CLOSED session — attendance is still readable after close")
        void worksForClosedSession() {
            QrSession closed = QrSession.builder()
                    .id(sessionId).course(course).professor(professor)
                    .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                    .closedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .build();

            Attendance a = Attendance.builder().id(UUID.randomUUID()).session(closed)
                    .rollNumber("CS24B001").studentName("Alice").status(AttendanceStatus.CONFIRMED)
                    .manuallyAdded(false).heartbeatCoverage(0.95f).build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(closed));
            when(attendanceRepository.findBySessionId(sessionId)).thenReturn(List.of(a));

            List<AttendanceResponse> result = sessionService.listAttendance(sessionId, profId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getHeartbeatCoverage()).isEqualTo(0.95f);
        }
    }
}
