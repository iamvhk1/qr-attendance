package com.qrattend.repository;

import com.qrattend.TestDataFactory;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.entity.Student;
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
class CourseRepositoryTest {

    @Autowired private CourseRepository courseRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private QrSessionRepository qrSessionRepository;
    @Autowired private TestEntityManager entityManager;

    private Professor professor;

    @BeforeEach
    void setUp() {
        professor = entityManager.persistAndFlush(TestDataFactory.professor());
    }

    // ── CRUD ────────────────────────────────────────────────

    @Test
    @DisplayName("Save and retrieve a course with a nullable semester")
    void saveAndRetrieveCourse() {
        Course course = TestDataFactory.course(professor, "CS101");
        course.setSemester(null); // Explicitly testing nullable field
        
        Course saved = courseRepository.save(course);
        entityManager.flush();
        entityManager.clear();

        Course found = courseRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getCode()).isEqualTo("CS101");
        assertThat(found.getSemester()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getProfessor().getId()).isEqualTo(professor.getId());
    }

    // ── Custom queries ──────────────────────────────────────

    @Test
    @DisplayName("findByProfessorId returns all courses for a given professor")
    void findByProfessorIdReturnsCourses() {
        courseRepository.save(TestDataFactory.course(professor, "CS101"));
        courseRepository.save(TestDataFactory.course(professor, "CS102"));
        entityManager.flush();

        List<Course> courses = courseRepository.findByProfessorId(professor.getId());

        assertThat(courses).hasSize(2);
    }

    @Test
    @DisplayName("findByProfessorId returns empty for a professor with no courses")
    void findByProfessorIdReturnsEmpty() {
        Professor newProfessor = entityManager.persistAndFlush(TestDataFactory.professor("new@iitm.ac.in"));
        
        List<Course> courses = courseRepository.findByProfessorId(newProfessor.getId());
        
        assertThat(courses).isEmpty();
    }

    // ── Isolation & Cascade ─────────────────────────────────

    @Test
    @DisplayName("Isolation: Professor A's courses do not appear in Professor B's queries")
    void coursesAreIsolatedByProfessor() {
        Professor professorB = entityManager.persistAndFlush(TestDataFactory.professor("profB@iitm.ac.in"));
        
        courseRepository.save(TestDataFactory.course(professor, "CS101"));
        courseRepository.save(TestDataFactory.course(professorB, "MA201"));
        entityManager.flush();

        List<Course> coursesA = courseRepository.findByProfessorId(professor.getId());
        List<Course> coursesB = courseRepository.findByProfessorId(professorB.getId());

        assertThat(coursesA).hasSize(1);
        assertThat(coursesA.get(0).getCode()).isEqualTo("CS101");
        
        assertThat(coursesB).hasSize(1);
        assertThat(coursesB.get(0).getCode()).isEqualTo("MA201");
    }

    @Test
    @DisplayName("Cascade delete: Deleting a course deletes its students and sessions")
    void cascadeDeleteRemovesChildren() {
        Course course = courseRepository.save(TestDataFactory.course(professor));
        entityManager.flush();
        
        studentRepository.save(TestDataFactory.student(course));
        qrSessionRepository.save(TestDataFactory.liveSession(course, professor));
        entityManager.flush();
        entityManager.clear();

        Course foundCourse = courseRepository.findById(course.getId()).orElseThrow();
        courseRepository.delete(foundCourse);
        entityManager.flush();
        entityManager.clear();

        assertThat(courseRepository.findById(course.getId())).isEmpty();
        assertThat(studentRepository.findAll()).isEmpty();
        assertThat(qrSessionRepository.findAll()).isEmpty();
    }
}
