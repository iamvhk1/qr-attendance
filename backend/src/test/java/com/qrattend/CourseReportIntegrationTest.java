package com.qrattend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.entity.*;
import com.qrattend.repository.*;
import com.qrattend.security.JwtUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the course attendance report: real database, real JWT, real HTTP.
 * Verifies the JSON the website reads and that the Excel download shows the same numbers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Course attendance report — end to end")
class CourseReportIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ProfessorRepository professorRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private QrSessionRepository sessionRepository;
    @Autowired private AttendanceRepository attendanceRepository;

    private String jwt;
    private Course course;
    private QrSession closed1, closed2, live;

    @BeforeEach
    void setUp() {
        Professor prof = professorRepository.save(TestDataFactory.professor("report@test.com"));
        jwt = jwtUtil.generateLoginToken(prof.getId(), prof.getEmail());
        course = courseRepository.save(TestDataFactory.course(prof, "CS7777"));

        studentRepository.save(TestDataFactory.student(course, "CS24B001", "Alice"));
        studentRepository.save(TestDataFactory.student(course, "CS24B002", "Bob"));

        closed1 = sessionRepository.save(TestDataFactory.closedSession(course, prof));
        closed2 = sessionRepository.save(TestDataFactory.expiredSession(course, prof));
        live    = sessionRepository.save(TestDataFactory.liveSession(course, prof));

        // Alice: present in both closed sessions; Bob: present in one, failed the other.
        attendanceRepository.save(TestDataFactory.confirmedAttendance(closed1, "CS24B001"));
        attendanceRepository.save(TestDataFactory.confirmedAttendance(closed2, "CS24B001"));
        attendanceRepository.save(TestDataFactory.confirmedAttendance(closed1, "CS24B002"));
        Attendance bobFailed = TestDataFactory.confirmedAttendance(closed2, "CS24B002");
        bobFailed.setStatus(AttendanceStatus.INVALIDATED);
        attendanceRepository.save(bobFailed);
        // Bob scanned the live session — shown, but must not count yet.
        attendanceRepository.save(TestDataFactory.confirmedAttendance(live, "CS24B002"));
    }

    private JsonNode fetchReport() throws Exception {
        String body = mockMvc.perform(get("/api/reports/courses/" + course.getId())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode student(JsonNode report, String roll) {
        for (JsonNode s : report.get("students")) {
            if (s.get("rollNumber").asText().equals(roll)) return s;
        }
        throw new AssertionError("No row for " + roll);
    }

    /** Session id → column index (sessions created in the same instant may tie on createdAt). */
    private Map<String, Integer> columnIndex(JsonNode report) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < report.get("sessions").size(); i++) {
            idx.put(report.get("sessions").get(i).get("id").asText(), i);
        }
        return idx;
    }

    @Test
    @DisplayName("Owner gets the full matrix with the counting rules applied")
    void ownerGetsMatrix() throws Exception {
        JsonNode report = fetchReport();

        assertThat(report.get("courseCode").asText()).isEqualTo("CS7777");
        assertThat(report.get("sessions").size()).isEqualTo(3);

        Map<String, Integer> col = columnIndex(report);
        JsonNode liveCol = report.get("sessions").get(col.get(live.getId().toString()));
        assertThat(liveCol.get("counted").asBoolean()).isFalse();
        assertThat(liveCol.get("status").asText()).isEqualTo("LIVE");
        assertThat(report.get("sessions").get(col.get(closed2.getId().toString())).get("counted").asBoolean())
                .as("an expired session counts as closed").isTrue();

        JsonNode alice = student(report, "CS24B001");
        assertThat(alice.get("attended").asInt()).isEqualTo(2);
        assertThat(alice.get("total").asInt()).isEqualTo(2);
        assertThat(alice.get("percentage").asDouble()).isEqualTo(100.0);
        assertThat(alice.get("belowThreshold").asBoolean()).isFalse();
        assertThat(alice.get("cells").get(col.get(live.getId().toString())).asText()).isEqualTo("ABSENT");

        JsonNode bob = student(report, "CS24B002");
        assertThat(bob.get("attended").asInt()).as("live CONFIRMED must not count").isEqualTo(1);
        assertThat(bob.get("percentage").asDouble()).isEqualTo(50.0);
        assertThat(bob.get("belowThreshold").asBoolean()).isTrue();
        assertThat(bob.get("cells").get(col.get(closed2.getId().toString())).asText()).isEqualTo("INVALIDATED");
        assertThat(bob.get("cells").get(col.get(live.getId().toString())).asText()).isEqualTo("CONFIRMED");

        JsonNode summary = report.get("summary");
        assertThat(summary.get("studentCount").asInt()).isEqualTo(2);
        assertThat(summary.get("countedSessions").asInt()).isEqualTo(2);
        assertThat(summary.get("liveSessions").asInt()).isEqualTo(1);
        assertThat(summary.get("averagePercentage").asDouble()).isEqualTo(75.0);
        assertThat(summary.get("belowThresholdCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("The Excel download shows the same per-student numbers as the JSON")
    void excelMatchesJson() throws Exception {
        JsonNode report = fetchReport();

        byte[] xlsx = mockMvc.perform(get("/api/reports/courses/" + course.getId() + "/excel")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 0; i < report.get("students").size(); i++) {
                JsonNode s = report.get("students").get(i);
                Row row = sheet.getRow(3 + i);
                assertThat(row.getCell(0).getStringCellValue()).isEqualTo(s.get("rollNumber").asText());
                assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(s.get("total").asDouble());
                assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(s.get("attended").asDouble());
                assertThat(row.getCell(4).getStringCellValue())
                        .isEqualTo(String.format("%.1f%%", s.get("percentage").asDouble()));
                for (int c = 0; c < s.get("cells").size(); c++) {
                    String cell = s.get("cells").get(c).asText();
                    assertThat(row.getCell(5 + c).getStringCellValue())
                            .isEqualTo("ABSENT".equals(cell) ? "Absent" : cell);
                }
            }
        }
    }

    @Test
    @DisplayName("A course with no sessions returns null percentages, not 0%")
    void courseWithoutSessions() throws Exception {
        attendanceRepository.deleteAll();
        sessionRepository.deleteAll(sessionRepository.findByCourseId(course.getId()));

        JsonNode report = fetchReport();

        assertThat(report.get("sessions").size()).isZero();
        assertThat(student(report, "CS24B001").get("percentage").isNull()).isTrue();
        assertThat(report.get("summary").get("averagePercentage").isNull()).isTrue();
        assertThat(report.get("summary").get("belowThresholdCount").asInt()).isZero();
    }

    @Test
    @DisplayName("Unknown course → 404")
    void unknownCourse() throws Exception {
        mockMvc.perform(get("/api/reports/courses/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());
    }
}
