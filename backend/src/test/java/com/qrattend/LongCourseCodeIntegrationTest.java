package com.qrattend;

import com.qrattend.config.CourseCodeColumnMigration;
import com.qrattend.entity.Professor;
import com.qrattend.repository.CourseRepository;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real stack: course codes have no length limit, and a database created with the old
 * VARCHAR(20) column is widened at startup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Course codes — no length limit")
class LongCourseCodeIntegrationTest {

    private static final String LONG_CODE =
            "CS6024-ADVANCED-TOPICS-IN-DISTRIBUTED-SYSTEMS-AND-CLOUD-COMPUTING-" + "X".repeat(300);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CourseCodeColumnMigration migration;
    @Autowired private ProfessorRepository professorRepository;
    @Autowired private CourseRepository courseRepository;

    private Professor professor;
    private String token;

    @BeforeEach
    void setUp() {
        professor = professorRepository.save(TestDataFactory.professor("longcode@iitm.ac.in"));
        token = jwtUtil.generateLoginToken(professor.getId(), professor.getEmail());
    }

    @AfterEach
    void tearDown() {
        courseRepository.findByProfessorId(professor.getId()).forEach(courseRepository::delete);
        professorRepository.delete(professor);
    }

    private void createCourse(String code, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Long code course\",\"code\":\"" + code + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("A course code far over 20 characters is accepted and stored in full")
    void longCodeAccepted() throws Exception {
        mockMvc.perform(post("/api/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Long code course\",\"code\":\"" + LONG_CODE + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(LONG_CODE));

        assertThat(courseRepository.findByProfessorId(professor.getId()))
                .singleElement()
                .satisfies(c -> assertThat(c.getCode()).isEqualTo(LONG_CODE));
    }

    @Test
    @DisplayName("A blank course code is still rejected")
    void blankCodeRejected() throws Exception {
        createCourse("  ", 400);
    }

    @Test
    @DisplayName("Startup migration widens an old VARCHAR(20) column, keeping NOT NULL")
    void migrationWidensOldColumn() throws Exception {
        // Recreate the pre-change schema, as on the live database.
        jdbcTemplate.execute("ALTER TABLE courses ALTER COLUMN code SET DATA TYPE VARCHAR(20)");
        int oldSchemaStatus = mockMvc.perform(post("/api/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Long code course\",\"code\":\"" + LONG_CODE + "\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(oldSchemaStatus).as("old column can't hold the code").isNotEqualTo(201);

        migration.run(null);
        createCourse(LONG_CODE, 201);

        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = 'courses' AND LOWER(column_name) = 'code'",
                String.class);
        assertThat(nullable).isEqualTo("NO");

        // Running again is a no-op.
        migration.run(null);
        createCourse(LONG_CODE + "-2", 201);
    }
}
