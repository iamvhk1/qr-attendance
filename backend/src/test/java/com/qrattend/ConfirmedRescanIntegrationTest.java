package com.qrattend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real stack, checking the scan invariants for one roll number in a live session:
 * <ol>
 *   <li>accepted (CONFIRMED) attendance can't be scanned again;</li>
 *   <li>invalidated attendance gets a fresh chance;</li>
 *   <li>while PENDING, only one scan is being verified at a time.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Scan invariants per roll number")
class ConfirmedRescanIntegrationTest {

    private static final String ROLL = "CS24B077";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ProfessorRepository professorRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private QrSessionRepository sessionRepository;
    @Autowired private AttendanceRepository attendanceRepository;

    private Professor professor;
    private Course course;
    private Student student;
    private QrSession session;

    @BeforeEach
    void setUp() {
        professor = professorRepository.save(TestDataFactory.professor("rescan@iitm.ac.in"));
        course = courseRepository.save(TestDataFactory.course(professor, "RESCAN101"));
        student = studentRepository.save(TestDataFactory.student(course, ROLL, "Test Student"));
        session = sessionRepository.save(TestDataFactory.liveSession(course, professor));
    }

    @AfterEach
    void tearDown() {
        attendanceRepository.findBySessionIdAndRollNumber(session.getId(), ROLL)
                .ifPresent(attendanceRepository::delete);
        sessionRepository.deleteById(session.getId());
        studentRepository.deleteById(student.getId());
        courseRepository.deleteById(course.getId());
        professorRepository.deleteById(professor.getId());
    }

    // ── helpers ──

    private MockHttpServletResponse scan() throws Exception {
        return mockMvc.perform(post("/api/student/scan")
                        .header("Authorization", "Bearer " + jwtUtil.generateScanToken(session.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"" + ROLL + "\"}"))
                .andReturn().getResponse();
    }

    private JsonNode heartbeat(String attendanceToken, String nonce) throws Exception {
        String body = mockMvc.perform(post("/api/student/heartbeat")
                        .header("Authorization", "Bearer " + attendanceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nonce\":\"" + nonce + "\",\"webdriver\":false}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void markPresent() throws Exception {
        String profToken = jwtUtil.generateLoginToken(professor.getId(), professor.getEmail());
        mockMvc.perform(post("/api/sessions/" + session.getId() + "/attendance/manual")
                        .header("Authorization", "Bearer " + profToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"" + ROLL + "\",\"reason\":\"Phone died\"}"))
                .andExpect(status().isCreated());
    }

    private Attendance current() {
        return attendanceRepository.findBySessionIdAndRollNumber(session.getId(), ROLL).orElseThrow();
    }

    /** Makes the existing record look like its tab has been silent for {@code seconds}. */
    private void silenceFor(long seconds) {
        Attendance a = current();
        a.setLastHeartbeatAt(Instant.now().minusSeconds(seconds));
        a.setPresenceStart(Instant.now().minusSeconds(seconds));
        attendanceRepository.save(a);
    }

    // ── 1. accepted → no rescan ──

    @Test
    @DisplayName("1. Marked present → scanning again is refused and it stays CONFIRMED")
    void acceptedCannotRescan() throws Exception {
        markPresent();

        MockHttpServletResponse res = scan();
        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentAsString()).contains("already marked present");
        assertThat(current().getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
    }

    @Test
    @DisplayName("1. Marked present while a tab was pending → the old tab's late heartbeat can't undo it")
    void lateHeartbeatCantUndoAccepted() throws Exception {
        JsonNode first = objectMapper.readTree(scan().getContentAsString());
        String token = first.get("attendanceToken").asText();
        String nonce = first.get("initialNonce").asText();

        markPresent();
        silenceFor(60); // the old tab comes back long after the gap limit

        JsonNode hb = heartbeat(token, nonce);
        assertThat(hb.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(current().getStatus()).isEqualTo(AttendanceStatus.CONFIRMED);
    }

    // ── 2. invalidated → fresh chance ──

    @Test
    @DisplayName("2. Invalidated → scanning again gives a fresh PENDING chance")
    void invalidatedCanRescan() throws Exception {
        assertThat(scan().getStatus()).isEqualTo(200);
        Attendance a = current();
        a.setStatus(AttendanceStatus.INVALIDATED);
        attendanceRepository.save(a);

        assertThat(scan().getStatus()).isEqualTo(200);
        assertThat(current().getStatus()).isEqualTo(AttendanceStatus.PENDING);
    }

    // ── 3. pending → only one scan at a time ──

    @Test
    @DisplayName("3. Pending and active → a second scan is refused; the first tab keeps working")
    void secondScanWhilePendingRefused() throws Exception {
        JsonNode first = objectMapper.readTree(scan().getContentAsString());
        String token = first.get("attendanceToken").asText();
        String nonce = first.get("initialNonce").asText();

        MockHttpServletResponse second = scan();
        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(second.getContentAsString()).contains("already being verified");

        // The original tab is unaffected: its nonce still works.
        JsonNode hb = heartbeat(token, nonce);
        assertThat(hb.get("status").asText()).isEqualTo("PENDING");
        assertThat(hb.get("nextNonce").asText()).isNotBlank();
    }

    @Test
    @DisplayName("3. Pending but its tab went silent past the grace period → counts as invalidated, rescan allowed")
    void silentPendingCanRescan() throws Exception {
        JsonNode first = objectMapper.readTree(scan().getContentAsString());
        String oldToken = first.get("attendanceToken").asText();
        String oldNonce = first.get("initialNonce").asText();

        silenceFor(30);

        assertThat(scan().getStatus()).isEqualTo(200);
        assertThat(current().getStatus()).isEqualTo(AttendanceStatus.PENDING);

        // Only the new scan is live: the old tab's nonce is now rejected.
        int oldTabStatus = mockMvc.perform(post("/api/student/heartbeat")
                        .header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nonce\":\"" + oldNonce + "\",\"webdriver\":false}"))
                .andReturn().getResponse().getStatus();
        assertThat(oldTabStatus).isEqualTo(400);
    }
}
