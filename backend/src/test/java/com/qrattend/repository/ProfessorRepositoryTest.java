package com.qrattend.repository;

import com.qrattend.TestDataFactory;
import com.qrattend.entity.Professor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class ProfessorRepositoryTest {

    @Autowired private ProfessorRepository professorRepository;
    @Autowired private TestEntityManager entityManager;

    // ── CRUD ────────────────────────────────────────────────

    @Test
    @DisplayName("Save and retrieve a professor")
    void saveAndRetrieveProfessor() {
        Professor saved = professorRepository.save(TestDataFactory.professor());
        entityManager.flush();
        entityManager.clear();

        Professor found = professorRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getFullName()).isEqualTo("Dr. Boby George");
        assertThat(found.getEmail()).isEqualTo("boby@iitm.ac.in");
        assertThat(found.getPasswordHash()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    // ── Custom queries ──────────────────────────────────────

    @Test
    @DisplayName("findByEmail returns an existing professor")
    void findByEmailReturnsExisting() {
        Professor saved = professorRepository.save(TestDataFactory.professor("admin@iitm.ac.in"));
        entityManager.flush();
        entityManager.clear();

        Optional<Professor> found = professorRepository.findByEmail("admin@iitm.ac.in");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findByEmail returns empty for unknown email")
    void findByEmailReturnsEmpty() {
        Optional<Professor> found = professorRepository.findByEmail("unknown@iitm.ac.in");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("existsByEmail detects existing and non-existing emails correctly")
    void existsByEmail() {
        professorRepository.save(TestDataFactory.professor("test@iitm.ac.in"));
        entityManager.flush();
        entityManager.clear();

        assertThat(professorRepository.existsByEmail("test@iitm.ac.in")).isTrue();
        assertThat(professorRepository.existsByEmail("nobody@iitm.ac.in")).isFalse();
    }

    // ── Constraints ─────────────────────────────────────────

    @Test
    @DisplayName("Saving two professors with the same email throws DataIntegrityViolationException")
    void duplicateEmailThrowsException() {
        professorRepository.save(TestDataFactory.professor("duplicate@iitm.ac.in"));
        entityManager.flush();

        assertThatThrownBy(() -> {
            professorRepository.saveAndFlush(TestDataFactory.professor("duplicate@iitm.ac.in"));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
