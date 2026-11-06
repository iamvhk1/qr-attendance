package com.qrattend.config;

import com.qrattend.TestDataFactory;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.entity.Student;
import com.qrattend.repository.CourseRepository;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.repository.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real database: the start-up backfill fills the case-insensitive roll key for old rows without
 * ever violating the uniqueness rule, and never stops the application from starting.
 */
@SpringBootTest
@DisplayName("StudentRollKeyBackfill")
class StudentRollKeyBackfillTest {

    @Autowired private StudentRollKeyBackfill backfill;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProfessorRepository professorRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private StudentRepository studentRepository;

    private Professor professor;
    private Course course;

    @BeforeEach
    void setUp() {
        professor = professorRepository.save(TestDataFactory.professor("backfill@iitm.ac.in"));
        course = courseRepository.save(TestDataFactory.course(professor, "BACKFILL101"));
    }

    @AfterEach
    void tearDown() {
        studentRepository.deleteAll(studentRepository.findByCourseId(course.getId()));
        courseRepository.deleteById(course.getId());
        professorRepository.deleteById(professor.getId());
    }

    private String keyOf(Student s) {
        return jdbcTemplate.queryForObject("SELECT roll_key FROM students WHERE id = ?", String.class, s.getId());
    }

    private void clearKey(Student s) {
        jdbcTemplate.update("UPDATE students SET roll_key = NULL WHERE id = ?", s.getId());
    }

    @Test
    @DisplayName("A new student gets its upper-case key automatically")
    void keyIsSetOnSave() {
        Student s = studentRepository.save(TestDataFactory.student(course, "cs24b010", "Ann"));
        assertThat(keyOf(s)).isEqualTo("CS24B010");
    }

    @Test
    @DisplayName("Old rows without a key are filled in")
    void fillsMissingKeys() {
        Student a = studentRepository.save(TestDataFactory.student(course, "CS24B011", "Ann"));
        Student b = studentRepository.save(TestDataFactory.student(course, "cs24b012", "Ben"));
        clearKey(a);
        clearKey(b);

        backfill.run(null);

        assertThat(keyOf(a)).isEqualTo("CS24B011");
        assertThat(keyOf(b)).isEqualTo("CS24B012");
    }

    @Test
    @DisplayName("Case-duplicates: only the oldest gets the key, the rest stay empty, nothing fails")
    void duplicatesKeepOldestOnly() throws Exception {
        Student oldest = studentRepository.save(TestDataFactory.student(course, "CS24B013", "First"));
        clearKey(oldest);
        Thread.sleep(20); // make the creation times differ
        Student newer = studentRepository.save(TestDataFactory.student(course, "cs24b013", "Second"));
        clearKey(newer);

        assertThatCode(() -> backfill.run(null)).doesNotThrowAnyException();

        assertThat(keyOf(oldest)).isEqualTo("CS24B013");
        assertThat(keyOf(newer)).isNull();
    }

    @Test
    @DisplayName("An old row never takes a key that a newer student of the course already holds")
    void doesNotCollideWithExistingKey() {
        Student old = studentRepository.save(TestDataFactory.student(course, "CS24B015", "Old"));
        clearKey(old);
        // A newer case-duplicate is inserted while the old row has no key, so it gets the key
        Student newer = studentRepository.save(TestDataFactory.student(course, "cs24b015", "Newer"));
        // An unrelated old row that must still be filled even though the one above is skipped
        Student other = studentRepository.save(TestDataFactory.student(course, "CS24B016", "Other"));
        clearKey(other);

        assertThatCode(() -> backfill.run(null)).doesNotThrowAnyException();

        assertThat(keyOf(newer)).isEqualTo("CS24B015");
        assertThat(keyOf(old)).isNull();
        assertThat(keyOf(other)).isEqualTo("CS24B016");
    }

    @Test
    @DisplayName("Running it again changes nothing")
    void isIdempotent() {
        Student a = studentRepository.save(TestDataFactory.student(course, "CS24B014", "Ann"));
        clearKey(a);

        backfill.run(null);
        backfill.run(null);

        assertThat(keyOf(a)).isEqualTo("CS24B014");
    }
}
