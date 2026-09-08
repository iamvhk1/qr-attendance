package com.qrattend.service;

import com.google.zxing.WriterException;
import com.qrattend.dto.session.SessionRequest;
import com.qrattend.dto.session.SessionResponse;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.entity.QrSession;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.exception.SessionClosedException;
import com.qrattend.repository.CourseRepository;
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

                byte[] result = sessionService.getQrImageBytes(sessionId, profId);

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

                sessionService.getQrImageBytes(sessionId, profId);
                sessionService.getQrImageBytes(sessionId, profId);
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

            assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId))
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

            assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId))
                    .isInstanceOf(SessionClosedException.class);

            verifyNoInteractions(jwtUtil);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when session does not exist")
        void throwsNotFoundWhenSessionAbsent() {
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId))
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

            assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, otherProfId))
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

                assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId))
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

                assertThatThrownBy(() -> sessionService.getQrImageBytes(sessionId, profId))
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

                sessionService.getQrImageBytes(sessionId, profId);

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

                sessionService.getQrImageBytes(sessionId, profId);
            }

            // The rolling QR design: session must NOT be saved/updated on each QR fetch
            verify(sessionRepository, never()).save(any());
        }
    }
}
