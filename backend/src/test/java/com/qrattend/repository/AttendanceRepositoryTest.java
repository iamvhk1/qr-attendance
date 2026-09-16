package com.qrattend.repository;

import com.qrattend.TestDataFactory;
import com.qrattend.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link AttendanceRepository}.
 *
 * Verifies the unique constraint (session_id + roll_number),
 * status-based queries (PENDING/CONFIRMED/INVALIDATED),
 * manual override fields, and presence verification fields.
 */
@DataJpaTest
class AttendanceRepositoryTest {

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Professor professor;
    private Course course;
    private QrSession session;

    @BeforeEach
    void setUp() {
        professor = entityManager.persistAndFlush(TestDataFactory.professor());
        course = entityManager.persistAndFlush(TestDataFactory.course(professor));
        session = entityManager.persistAndFlush(TestDataFactory.liveSession(course, professor));
    }

    // ── CRUD ────────────────────────────────────────────────

    @Test
    @DisplayName("Save and retrieve a confirmed attendance record")
    void saveConfirmedAttendance() {
        Attendance saved = attendanceRepository.save(
                TestDataFactory.confirmedAttendance(session, "CS24B001"));
        entityManager.flush();
        entityManager.clear();

        Attendance found = attendanceRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getRollNumber()).isEqualTo("CS24B001");
        assertThat(found.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
        assertThat(found.getManuallyAdded()).isFalse();
        assertThat(found.getMarkedAt()).isNotNull();
    }

    @Test
    @DisplayName("Save a pending attendance record with presence verification fields")
    void savePendingAttendance() {
        Attendance saved = attendanceRepository.save(
                TestDataFactory.pendingAttendance(session, "CS24B002"));
        entityManager.flush();
        entityManager.clear();

        Attendance found = attendanceRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(AttendanceStatus.PENDING);
        assertThat(found.getPresenceStart()).isNotNull();
        assertThat(found.getPresenceEnd()).isNotNull();
        assertThat(found.getPresenceEnd()).isAfter(found.getPresenceStart());
        assertThat(found.getHeartbeatCoverage()).isNull(); // not computed yet
    }

    @Test
    @DisplayName("Save a manual override attendance record")
    void saveManualAttendance() {
        Attendance saved = attendanceRepository.save(
                TestDataFactory.manualAttendance(session, "CS24B003", professor));
        entityManager.flush();
        entityManager.clear();

        Attendance found = attendanceRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getManuallyAdded()).isTrue();
        assertThat(found.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED); // manual = immediately confirmed
        assertThat(found.getOverrideReason()).isEqualTo("Phone battery died");
        assertThat(found.getAddedByProfessor()).isNotNull();
        assertThat(found.getAddedByProfessor().getId()).isEqualTo(professor.getId());
    }

    // ── Custom queries ──────────────────────────────────────

    @Test
    @DisplayName("findBySessionId returns all attendance for that session")
    void findBySessionId() {
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B001"));
        attendanceRepository.save(TestDataFactory.pendingAttendance(session, "CS24B002"));
        attendanceRepository.save(TestDataFactory.manualAttendance(session, "CS24B003", professor));
        entityManager.flush();

        List<Attendance> records = attendanceRepository.findBySessionId(session.getId());

        assertThat(records).hasSize(3);
    }

    @Test
    @DisplayName("findBySessionIdAndRollNumber returns the specific record")
    void findBySessionIdAndRollNumber() {
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B001"));
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B002"));
        entityManager.flush();

        Optional<Attendance> found = attendanceRepository.findBySessionIdAndRollNumber(
                session.getId(), "CS24B002");

        assertThat(found).isPresent();
        assertThat(found.get().getRollNumber()).isEqualTo("CS24B002");
    }

    @Test
    @DisplayName("existsBySessionIdAndRollNumber detects existing records")
    void existsBySessionIdAndRollNumber() {
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B001"));
        entityManager.flush();

        assertThat(attendanceRepository.existsBySessionIdAndRollNumber(
                session.getId(), "CS24B001")).isTrue();
        assertThat(attendanceRepository.existsBySessionIdAndRollNumber(
                session.getId(), "CS24B999")).isFalse();
    }

    @Test
    @DisplayName("findBySessionIdAndStatus filters by status correctly")
    void findBySessionIdAndStatus() {
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B001"));
        attendanceRepository.save(TestDataFactory.pendingAttendance(session, "CS24B002"));
        attendanceRepository.save(TestDataFactory.pendingAttendance(session, "CS24B003"));
        entityManager.flush();

        List<Attendance> pending = attendanceRepository.findBySessionIdAndStatus(
                session.getId(), AttendanceStatus.PENDING);
        List<Attendance> confirmed = attendanceRepository.findBySessionIdAndStatus(
                session.getId(), AttendanceStatus.CONFIRMED);

        assertThat(pending).hasSize(2);
        assertThat(confirmed).hasSize(1);
    }

    @Test
    @DisplayName("countBySessionIdAndStatus returns correct counts")
    void countBySessionIdAndStatus() {
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B001"));
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B002"));
        attendanceRepository.save(TestDataFactory.pendingAttendance(session, "CS24B003"));
        entityManager.flush();

        assertThat(attendanceRepository.countBySessionIdAndStatus(session.getId(), AttendanceStatus.CONFIRMED)).isEqualTo(2);
        assertThat(attendanceRepository.countBySessionIdAndStatus(session.getId(), AttendanceStatus.PENDING)).isEqualTo(1);
        assertThat(attendanceRepository.countBySessionIdAndStatus(session.getId(), AttendanceStatus.INVALIDATED)).isEqualTo(0);
    }

    // ── Constraints ─────────────────────────────────────────

    @Test
    @DisplayName("Duplicate (session_id, roll_number) throws DataIntegrityViolationException")
    void duplicateAttendanceFails() {
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B001"));
        entityManager.flush();

        Attendance duplicate = TestDataFactory.confirmedAttendance(session, "CS24B001");

        assertThatThrownBy(() -> {
            attendanceRepository.saveAndFlush(duplicate);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Same roll number in DIFFERENT sessions is allowed")
    void sameRollDifferentSession() {
        QrSession session2 = entityManager.persistAndFlush(TestDataFactory.liveSession(course, professor));

        attendanceRepository.save(TestDataFactory.confirmedAttendance(session, "CS24B001"));
        attendanceRepository.save(TestDataFactory.confirmedAttendance(session2, "CS24B001"));
        entityManager.flush();

        assertThat(attendanceRepository.findBySessionIdAndRollNumber(session.getId(), "CS24B001")).isPresent();
        assertThat(attendanceRepository.findBySessionIdAndRollNumber(session2.getId(), "CS24B001")).isPresent();
    }

    // ── Status transitions ──────────────────────────────────

    @Test
    @DisplayName("Status can be updated from PENDING to CONFIRMED")
    void statusTransitionPendingToConfirmed() {
        Attendance attendance = attendanceRepository.save(
                TestDataFactory.pendingAttendance(session, "CS24B001"));
        entityManager.flush();

        attendance.setStatus(AttendanceStatus.CONFIRMED);
        attendance.setHeartbeatCoverage(0.92f);
        entityManager.flush();
        entityManager.clear();

        Attendance found = attendanceRepository.findById(attendance.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
        assertThat(found.getHeartbeatCoverage()).isEqualTo(0.92f);
    }

    @Test
    @DisplayName("Status can be updated from PENDING to INVALIDATED")
    void statusTransitionPendingToInvalidated() {
        Attendance attendance = attendanceRepository.save(
                TestDataFactory.pendingAttendance(session, "CS24B001"));
        entityManager.flush();

        attendance.setStatus(AttendanceStatus.INVALIDATED);
        attendance.setHeartbeatCoverage(0.45f);
        entityManager.flush();
        entityManager.clear();

        Attendance found = attendanceRepository.findById(attendance.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(AttendanceStatus.INVALIDATED);
        assertThat(found.getHeartbeatCoverage()).isEqualTo(0.45f);
    }
}
