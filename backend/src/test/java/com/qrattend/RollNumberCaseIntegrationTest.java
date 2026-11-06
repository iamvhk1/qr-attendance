package com.qrattend;

import com.qrattend.entity.*;
import com.qrattend.repository.*;
import com.qrattend.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real stack: a roll number typed in a different letter case, or with stray spaces, must
 * still find the enrolled student, for manual attendance, student scans and adding students.
 * The student is enrolled as "CS24B077"; the attendance record always keeps that spelling.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Roll numbers are matched case-insensitively")
class RollNumberCaseIntegrationTest {

    private static final String ROLL = "CS24B077";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ProfessorRepository professorRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private QrSessionRepository sessionRepository;
    @Autowired private AttendanceRepository attendanceRepository;

    private Professor professor;
    private Course course;
    private QrSession session;

    @BeforeEach
    void setUp() {
        professor = professorRepository.save(TestDataFactory.professor("rollcase@iitm.ac.in"));
        course = courseRepository.save(TestDataFactory.course(professor, "ROLLCASE101"));
        studentRepository.save(TestDataFactory.student(course, ROLL, "Case Student"));
        session = sessionRepository.save(TestDataFactory.liveSession(course, professor));
    }

    @AfterEach
    void tearDown() {
        attendanceRepository.deleteAll(attendanceRecords());
        sessionRepository.deleteById(session.getId());
        studentRepository.deleteAll(studentRepository.findByCourseId(course.getId()));
        courseRepository.deleteById(course.getId());
        professorRepository.deleteById(professor.getId());
    }

    // ── helpers ──

    private List<Attendance> attendanceRecords() {
        return attendanceRepository.findAllByCourseId(course.getId());
    }

    private MockHttpServletResponse manual(String typedRoll) throws Exception {
        String profToken = jwtUtil.generateLoginToken(professor.getId(), professor.getEmail());
        return mockMvc.perform(post("/api/sessions/" + session.getId() + "/attendance/manual")
                        .header("Authorization", "Bearer " + profToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"" + typedRoll + "\",\"reason\":\"Phone died\"}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse scan(String typedRoll) throws Exception {
        return mockMvc.perform(post("/api/student/scan")
                        .header("Authorization", "Bearer " + jwtUtil.generateScanToken(session.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"" + typedRoll + "\"}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse addStudent(String typedRoll) throws Exception {
        String profToken = jwtUtil.generateLoginToken(professor.getId(), professor.getEmail());
        return mockMvc.perform(post("/api/courses/" + course.getId() + "/students")
                        .header("Authorization", "Bearer " + profToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"" + typedRoll + "\",\"fullName\":\"Someone\"}"))
                .andReturn().getResponse();
    }

    // ── manual attendance (the reported bug) ──

    @Test
    @DisplayName("Manual attendance: lower-case roll number finds the student and keeps the enrolled spelling")
    void manualLowerCase() throws Exception {
        assertThat(manual("cs24b077").getStatus()).isEqualTo(201);

        assertThat(attendanceRecords()).hasSize(1);
        Attendance a = attendanceRecords().get(0);
        assertThat(a.getRollNumber()).isEqualTo(ROLL);
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Manual attendance: mixed case and surrounding spaces work")
    void manualMixedCaseAndSpaces() throws Exception {
        assertThat(manual("Cs24B077").getStatus()).isEqualTo(201);
        assertThat(manual("  CS24B077 ").getStatus()).isEqualTo(201);

        // Same student both times, so still exactly one record
        assertThat(attendanceRecords()).hasSize(1);
    }

    @Test
    @DisplayName("Manual attendance: a roll number that is not enrolled is still rejected (404)")
    void manualUnknownRollStillRejected() throws Exception {
        MockHttpServletResponse res = manual("cs24b999");
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(attendanceRecords()).isEmpty();
    }

    // ── student scan ──

    @Test
    @DisplayName("Scan: a student typing the roll number in lower case is accepted, stored as enrolled")
    void scanLowerCase() throws Exception {
        assertThat(scan("cs24b077").getStatus()).isEqualTo(200);

        assertThat(attendanceRecords()).hasSize(1);
        assertThat(attendanceRecords().get(0).getRollNumber()).isEqualTo(ROLL);
    }

    @Test
    @DisplayName("Scan then manual in a different case → one record, confirmed (no duplicate)")
    void scanThenManualSameRecord() throws Exception {
        assertThat(scan("cs24b077").getStatus()).isEqualTo(200);
        assertThat(manual("Cs24b077").getStatus()).isEqualTo(201);

        assertThat(attendanceRecords()).hasSize(1);
        assertThat(attendanceRecords().get(0).getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Manual then scan in a different case → refused, accepted attendance is final")
    void manualThenScanRefused() throws Exception {
        assertThat(manual(ROLL).getStatus()).isEqualTo(201);

        MockHttpServletResponse res = scan("cs24b077");
        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentAsString()).contains("already marked present");
        assertThat(attendanceRecords()).hasSize(1);
    }

    // ── adding students ──

    @Test
    @DisplayName("Adding a student whose roll number differs only in case is a duplicate (409)")
    void addStudentDuplicateIgnoringCase() throws Exception {
        assertThat(addStudent("cs24b077").getStatus()).isEqualTo(409);
        assertThat(studentRepository.findByCourseId(course.getId())).hasSize(1);
    }

    @Test
    @DisplayName("Adding a genuinely new roll number still works")
    void addStudentNewRoll() throws Exception {
        assertThat(addStudent("cs24b078").getStatus()).isEqualTo(201);
        assertThat(studentRepository.findByCourseId(course.getId())).hasSize(2);
    }
}
