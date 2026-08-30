package com.qrattend.repository;

import com.qrattend.TestDataFactory;
import com.qrattend.entity.Course;
import com.qrattend.entity.Heartbeat;
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
class HeartbeatRepositoryTest {

    @Autowired private HeartbeatRepository heartbeatRepository;
    @Autowired private TestEntityManager entityManager;

    private Professor professor;
    private Course course;
    private QrSession session;

    @BeforeEach
    void setUp() {
        professor = entityManager.persistAndFlush(TestDataFactory.professor());
        course    = entityManager.persistAndFlush(TestDataFactory.course(professor));
        session   = entityManager.persistAndFlush(TestDataFactory.liveSession(course, professor));
    }

    // ── CRUD & Defaults ─────────────────────────────────────

    @Test
    @DisplayName("Save a heartbeat and auto-populate receivedAt")
    void saveHeartbeat() {
        Heartbeat saved = heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        entityManager.flush();
        entityManager.clear();

        Heartbeat found = heartbeatRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getRollNumber()).isEqualTo("CS24B001");
        assertThat(found.getReceivedAt()).isNotNull();
        assertThat(found.getSession().getId()).isEqualTo(session.getId());
    }

    // ── Custom queries ──────────────────────────────────────

    @Test
    @DisplayName("findBySessionIdAndRollNumberOrderByReceivedAtAsc returns chronologically")
    void findOrderedHeartbeats() throws InterruptedException {
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        entityManager.flush();
        
        Thread.sleep(10);
        
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        entityManager.flush();
        entityManager.clear();

        List<Heartbeat> heartbeats = heartbeatRepository
                .findBySessionIdAndRollNumberOrderByReceivedAtAsc(session.getId(), "CS24B001");

        assertThat(heartbeats).hasSize(2);
        assertThat(heartbeats.get(1).getReceivedAt()).isAfter(heartbeats.get(0).getReceivedAt());
    }

    @Test
    @DisplayName("countBySessionIdAndRollNumber returns accurate count for multiple heartbeats")
    void countHeartbeats() {
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        entityManager.flush();

        long count = heartbeatRepository.countBySessionIdAndRollNumber(session.getId(), "CS24B001");

        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("countBySessionIdAndRollNumber returns 0 for a student with no heartbeats")
    void countHeartbeatsReturnsZero() {
        long count = heartbeatRepository.countBySessionIdAndRollNumber(session.getId(), "CS24B999");
        assertThat(count).isEqualTo(0);
    }

    // ── Constraints & Isolation ─────────────────────────────

    @Test
    @DisplayName("Isolation: Student A's count does not affect Student B's count")
    void heartbeatCountsAreIsolated() {
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B002"));
        entityManager.flush();

        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session.getId(), "CS24B001")).isEqualTo(2);
        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session.getId(), "CS24B002")).isEqualTo(1);
    }

    @Test
    @DisplayName("Multiple heartbeats for the same student and session are allowed")
    void multipleHeartbeatsAllowed() {
        heartbeatRepository.save(TestDataFactory.heartbeat(session, "CS24B001"));
        
        // This would throw an exception if a unique constraint incorrectly existed
        heartbeatRepository.saveAndFlush(TestDataFactory.heartbeat(session, "CS24B001"));
        
        assertThat(heartbeatRepository.count()).isEqualTo(2);
    }
}
