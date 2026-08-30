package com.qrattend.repository;

import com.qrattend.TestDataFactory;
import com.qrattend.entity.Course;
import com.qrattend.entity.Doubt;
import com.qrattend.entity.Professor;
import com.qrattend.entity.QrSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class DoubtRepositoryTest {

    @Autowired private DoubtRepository doubtRepository;
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
    @DisplayName("Save an anonymous doubt and auto-populate postedAt")
    void saveDoubt() {
        Doubt saved = doubtRepository.save(TestDataFactory.doubt(session, "What is paging?"));
        entityManager.flush();
        entityManager.clear();

        Doubt found = doubtRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getQuestion()).isEqualTo("What is paging?");
        assertThat(found.getPostedAt()).isNotNull();
        assertThat(found.getSession().getId()).isEqualTo(session.getId());
    }

    // ── Custom queries ──────────────────────────────────────

    @Test
    @DisplayName("findBySessionIdOrderByPostedAtAsc returns doubts in chronological order")
    void findOrderedDoubts() throws InterruptedException {
        doubtRepository.save(TestDataFactory.doubt(session, "First question"));
        entityManager.flush();
        
        Thread.sleep(10);
        
        doubtRepository.save(TestDataFactory.doubt(session, "Second question"));
        entityManager.flush();
        entityManager.clear();

        List<Doubt> doubts = doubtRepository.findBySessionIdOrderByPostedAtAsc(session.getId());

        assertThat(doubts).hasSize(2);
        assertThat(doubts.get(0).getQuestion()).isEqualTo("First question");
        assertThat(doubts.get(1).getQuestion()).isEqualTo("Second question");
        assertThat(doubts.get(1).getPostedAt()).isAfter(doubts.get(0).getPostedAt());
    }

    // ── Isolation & Constraints ─────────────────────────────

    @Test
    @DisplayName("Isolation: Doubts from session A do not appear in session B")
    void doubtsAreIsolatedBySession() {
        QrSession sessionB = entityManager.persistAndFlush(TestDataFactory.liveSession(course, professor));

        doubtRepository.save(TestDataFactory.doubt(session, "Session A doubt"));
        doubtRepository.save(TestDataFactory.doubt(sessionB, "Session B doubt"));
        entityManager.flush();

        List<Doubt> sessionADoubts = doubtRepository.findBySessionIdOrderByPostedAtAsc(session.getId());
        List<Doubt> sessionBDoubts = doubtRepository.findBySessionIdOrderByPostedAtAsc(sessionB.getId());

        assertThat(sessionADoubts).hasSize(1);
        assertThat(sessionADoubts.get(0).getQuestion()).isEqualTo("Session A doubt");
        
        assertThat(sessionBDoubts).hasSize(1);
        assertThat(sessionBDoubts.get(0).getQuestion()).isEqualTo("Session B doubt");
    }

    @Test
    @DisplayName("Multiple doubts from the same session are allowed")
    void multipleDoubtsAllowed() {
        doubtRepository.save(TestDataFactory.doubt(session, "Question 1"));
        doubtRepository.saveAndFlush(TestDataFactory.doubt(session, "Question 2"));
        
        assertThat(doubtRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Saving a doubt with question = null throws DataIntegrityViolationException")
    void nullQuestionThrowsException() {
        // Factory requires a string, so using inline builder to test NOT NULL constraint
        Doubt invalidDoubt = Doubt.builder()
                .session(session)
                .question(null)
                .build();

        assertThatThrownBy(() -> {
            doubtRepository.saveAndFlush(invalidDoubt);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
