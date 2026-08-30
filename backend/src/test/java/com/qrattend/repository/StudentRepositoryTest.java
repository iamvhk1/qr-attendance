package com.qrattend.repository;

import com.qrattend.TestDataFactory;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.entity.Student;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class StudentRepositoryTest {

    @Autowired private StudentRepository studentRepository;
    @Autowired private TestEntityManager entityManager;

    private Professor professor;
    private Course course;

    @BeforeEach
    void setUp() {
        professor = entityManager.persistAndFlush(TestDataFactory.professor());
        course    = entityManager.persistAndFlush(TestDataFactory.course(professor));
    }

    // ── CRUD ────────────────────────────────────────────────

    @Test
    @DisplayName("Save and retrieve a student")
    void saveAndRetrieveStudent() {
        Student saved = studentRepository.save(TestDataFactory.student(course, "CS24B001", "Alice"));
        entityManager.flush();
        entityManager.clear();

        Student found = studentRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getRollNumber()).isEqualTo("CS24B001");
        assertThat(found.getFullName()).isEqualTo("Alice");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getCourse().getId()).isEqualTo(course.getId());
    }

    // ── Custom queries ──────────────────────────────────────

    @Test
    @DisplayName("findByCourseId returns all students for a course")
    void findByCourseId() {
        studentRepository.save(TestDataFactory.student(course, "CS24B001", "Alice"));
        studentRepository.save(TestDataFactory.student(course, "CS24B002", "Bob"));
        entityManager.flush();

        List<Student> students = studentRepository.findByCourseId(course.getId());

        assertThat(students).hasSize(2);
    }

    @Test
    @DisplayName("findByCourseId returns empty for a course with no students")
    void findByCourseIdReturnsEmpty() {
        Course emptyCourse = entityManager.persistAndFlush(TestDataFactory.course(professor, "EE101"));
        
        List<Student> students = studentRepository.findByCourseId(emptyCourse.getId());
        
        assertThat(students).isEmpty();
    }

    @Test
    @DisplayName("findByCourseIdAndRollNumber returns the specific student")
    void findByCourseIdAndRollNumber() {
        studentRepository.save(TestDataFactory.student(course, "CS24B001", "Alice"));
        entityManager.flush();

        Optional<Student> found = studentRepository.findByCourseIdAndRollNumber(course.getId(), "CS24B001");

        assertThat(found).isPresent();
        assertThat(found.get().getFullName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("existsByCourseIdAndRollNumber detects existing records")
    void existsByCourseIdAndRollNumber() {
        studentRepository.save(TestDataFactory.student(course, "CS24B001", "Alice"));
        entityManager.flush();

        assertThat(studentRepository.existsByCourseIdAndRollNumber(course.getId(), "CS24B001")).isTrue();
        assertThat(studentRepository.existsByCourseIdAndRollNumber(course.getId(), "CS24B999")).isFalse();
    }

    @Test
    @DisplayName("deleteByCourseIdAndRollNumberIn bulk deletes specified students")
    void deleteByCourseIdAndRollNumberIn() {
        studentRepository.save(TestDataFactory.student(course, "CS24B001", "Alice"));
        studentRepository.save(TestDataFactory.student(course, "CS24B002", "Bob"));
        studentRepository.save(TestDataFactory.student(course, "CS24B003", "Charlie"));
        entityManager.flush();

        studentRepository.deleteByCourseIdAndRollNumberIn(course.getId(), List.of("CS24B001", "CS24B003"));
        entityManager.flush();
        entityManager.clear();

        List<Student> remaining = studentRepository.findAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getRollNumber()).isEqualTo("CS24B002");
    }

    // ── Constraints ─────────────────────────────────────────

    @Test
    @DisplayName("Same roll number in the same course throws DataIntegrityViolationException")
    void duplicateRollNumberInSameCourseThrows() {
        studentRepository.save(TestDataFactory.student(course, "CS24B001", "Alice"));
        entityManager.flush();

        assertThatThrownBy(() -> {
            studentRepository.saveAndFlush(TestDataFactory.student(course, "CS24B001", "Duplicate Alice"));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Same roll number in DIFFERENT courses is allowed")
    void sameRollNumberInDifferentCoursesAllowed() {
        Course course2 = entityManager.persistAndFlush(TestDataFactory.course(professor, "EE101"));

        studentRepository.save(TestDataFactory.student(course, "CS24B001", "Alice"));
        studentRepository.save(TestDataFactory.student(course2, "CS24B001", "Alice"));
        entityManager.flush();

        assertThat(studentRepository.findByCourseIdAndRollNumber(course.getId(), "CS24B001")).isPresent();
        assertThat(studentRepository.findByCourseIdAndRollNumber(course2.getId(), "CS24B001")).isPresent();
    }
}
