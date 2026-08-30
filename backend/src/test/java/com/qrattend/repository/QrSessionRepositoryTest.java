package com.qrattend.repository;

import com.qrattend.TestDataFactory;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.entity.QrSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class QrSessionRepositoryTest {

    @Autowired private QrSessionRepository qrSessionRepository;
    @Autowired private TestEntityManager entityManager;

    private Professor professor;
    private Course course;

    @BeforeEach
    void setUp() {
        professor = entityManager.persistAndFlush(TestDataFactory.professor());
        course    = entityManager.persistAndFlush(TestDataFactory.course(professor));
    }

    // ── Domain Logic & Defaults ─────────────────────────────

    @Test
    @DisplayName("A newly created live session is not closed and has valid defaults")
    void liveSessionProperties() {
        QrSession saved = qrSessionRepository.save(TestDataFactory.liveSession(course, professor));
        entityManager.flush();
        entityManager.clear();

        QrSession found = qrSessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.isClosed()).isFalse();
        assertThat(found.getClosedAt()).isNull();
        assertThat(found.getExtendedCount()).isEqualTo(0);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("An expired session correctly reports as closed")
    void expiredSessionIsClosed() {
        QrSession saved = qrSessionRepository.save(TestDataFactory.expiredSession(course, professor));
        entityManager.flush();
        entityManager.clear();

        QrSession found = qrSessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.isClosed()).isTrue();
    }

    @Test
    @DisplayName("An explicitly closed session reports as closed even if expiresAt is in the future")
    void explicitlyClosedSessionIsClosed() {
        QrSession saved = qrSessionRepository.save(TestDataFactory.closedSession(course, professor));
        entityManager.flush();
        entityManager.clear();

        QrSession found = qrSessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.isClosed()).isTrue();
        assertThat(found.getClosedAt()).isNotNull();
    }

    // ── Custom queries ──────────────────────────────────────

    @Test
    @DisplayName("findByCourseId returns all sessions for a course")
    void findByCourseId() {
        qrSessionRepository.save(TestDataFactory.liveSession(course, professor));
        qrSessionRepository.save(TestDataFactory.expiredSession(course, professor));
        entityManager.flush();

        List<QrSession> sessions = qrSessionRepository.findByCourseId(course.getId());

        assertThat(sessions).hasSize(2);
    }

    @Test
    @DisplayName("findByCourseIdOrderByCreatedAtDesc returns sessions most-recent-first")
    void findByCourseIdOrderByCreatedAtDesc() throws InterruptedException {
        QrSession session1 = qrSessionRepository.save(TestDataFactory.expiredSession(course, professor));
        entityManager.flush();
        
        Thread.sleep(10); // Ensure distinct timestamps
        
        QrSession session2 = qrSessionRepository.save(TestDataFactory.liveSession(course, professor));
        entityManager.flush();
        entityManager.clear();

        List<QrSession> sessions = qrSessionRepository.findByCourseIdOrderByCreatedAtDesc(course.getId());

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getId()).isEqualTo(session2.getId());
        assertThat(sessions.get(1).getId()).isEqualTo(session1.getId());
    }
}
