package com.qrattend.controller;

import com.qrattend.dto.report.CourseAttendanceReport;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.GlobalExceptionHandler;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.security.JwtAuthenticationEntryPoint;
import com.qrattend.security.JwtAuthenticationFilter;
import com.qrattend.security.JwtUtil;
import com.qrattend.service.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer tests for {@link ReportController}. Security filters are disabled;
 * the SecurityContext is populated manually to simulate an authenticated professor.
 */
@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("ReportController")
class ReportControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ReportService reportService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private final UUID profId   = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                profId, null, List.of(new SimpleGrantedAuthority("ROLE_PROFESSOR"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("GET /api/reports/courses/{id}")
    class GetCourseReport {

        private CourseAttendanceReport sampleReport() {
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            return CourseAttendanceReport.builder()
                    .courseId(courseId).courseName("Operating Systems").courseCode("CS5013")
                    .sessions(List.of(
                            CourseAttendanceReport.SessionColumn.builder().id(s1)
                                    .createdAt(Instant.parse("2026-11-01T09:00:00Z"))
                                    .status("CLOSED").counted(true).confirmedCount(1).build(),
                            CourseAttendanceReport.SessionColumn.builder().id(s2)
                                    .createdAt(Instant.parse("2026-11-02T09:00:00Z"))
                                    .status("LIVE").counted(false).confirmedCount(0).build()))
                    .students(List.of(
                            CourseAttendanceReport.StudentRow.builder()
                                    .rollNumber("CS24B001").fullName("Alice")
                                    .attended(1).total(1).percentage(100.0).belowThreshold(false)
                                    .cells(List.of("CONFIRMED", "ABSENT")).build(),
                            CourseAttendanceReport.StudentRow.builder()
                                    .rollNumber("CS24B002").fullName("Bob")
                                    .attended(0).total(1).percentage(0.0).belowThreshold(true)
                                    .cells(List.of("ABSENT", "PENDING")).build()))
                    .summary(CourseAttendanceReport.Summary.builder()
                            .studentCount(2).countedSessions(1).liveSessions(1)
                            .averagePercentage(50.0).belowThresholdCount(1).threshold(75.0).build())
                    .build();
        }

        @Test
        @DisplayName("Returns 200 with the report serialised in the shape the frontend reads")
        void returns200WithReport() throws Exception {
            given(reportService.buildCourseReport(courseId, profId)).willReturn(sampleReport());

            mockMvc.perform(get("/api/reports/courses/" + courseId))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.courseId").value(courseId.toString()))
                    .andExpect(jsonPath("$.courseName").value("Operating Systems"))
                    .andExpect(jsonPath("$.courseCode").value("CS5013"))
                    .andExpect(jsonPath("$.sessions", hasSize(2)))
                    .andExpect(jsonPath("$.sessions[0].status").value("CLOSED"))
                    .andExpect(jsonPath("$.sessions[0].counted").value(true))
                    .andExpect(jsonPath("$.sessions[0].confirmedCount").value(1))
                    .andExpect(jsonPath("$.sessions[0].createdAt").exists())
                    .andExpect(jsonPath("$.sessions[1].counted").value(false))
                    .andExpect(jsonPath("$.students", hasSize(2)))
                    .andExpect(jsonPath("$.students[0].rollNumber").value("CS24B001"))
                    .andExpect(jsonPath("$.students[0].fullName").value("Alice"))
                    .andExpect(jsonPath("$.students[0].attended").value(1))
                    .andExpect(jsonPath("$.students[0].total").value(1))
                    .andExpect(jsonPath("$.students[0].percentage").value(100.0))
                    .andExpect(jsonPath("$.students[0].belowThreshold").value(false))
                    .andExpect(jsonPath("$.students[0].cells[0]").value("CONFIRMED"))
                    .andExpect(jsonPath("$.students[1].belowThreshold").value(true))
                    .andExpect(jsonPath("$.students[1].cells[1]").value("PENDING"))
                    .andExpect(jsonPath("$.summary.studentCount").value(2))
                    .andExpect(jsonPath("$.summary.countedSessions").value(1))
                    .andExpect(jsonPath("$.summary.liveSessions").value(1))
                    .andExpect(jsonPath("$.summary.averagePercentage").value(50.0))
                    .andExpect(jsonPath("$.summary.belowThresholdCount").value(1))
                    .andExpect(jsonPath("$.summary.threshold").value(75.0));
        }

        @Test
        @DisplayName("A null percentage (no counted sessions) is serialised as null")
        void nullPercentageSerialisedAsNull() throws Exception {
            CourseAttendanceReport report = sampleReport();
            report.getStudents().get(0).setPercentage(null);
            report.getSummary().setAveragePercentage(null);
            given(reportService.buildCourseReport(courseId, profId)).willReturn(report);

            mockMvc.perform(get("/api/reports/courses/" + courseId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.students[0].percentage").value(nullValue()))
                    .andExpect(jsonPath("$.summary.averagePercentage").value(nullValue()));
        }

        @Test
        @DisplayName("Passes the authenticated professor's ID to the service for the ownership check")
        void passesProfessorId() throws Exception {
            given(reportService.buildCourseReport(courseId, profId)).willReturn(sampleReport());

            mockMvc.perform(get("/api/reports/courses/" + courseId)).andExpect(status().isOk());

            then(reportService).should().buildCourseReport(courseId, profId);
        }

        @Test
        @DisplayName("Returns 403 when the professor does not own the course")
        void returns403() throws Exception {
            given(reportService.buildCourseReport(courseId, profId))
                    .willThrow(new ForbiddenException("You do not own this course"));

            mockMvc.perform(get("/api/reports/courses/" + courseId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Returns 404 when the course does not exist")
        void returns404() throws Exception {
            given(reportService.buildCourseReport(courseId, profId))
                    .willThrow(new ResourceNotFoundException("Course not found"));

            mockMvc.perform(get("/api/reports/courses/" + courseId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 400 for a malformed course ID")
        void returns400ForMalformedId() throws Exception {
            mockMvc.perform(get("/api/reports/courses/not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Does not clash with the Excel download route")
        void excelRouteStillSeparate() throws Exception {
            given(reportService.generateCourseExcel(courseId, profId)).willReturn(new byte[]{1, 2, 3});

            mockMvc.perform(get("/api/reports/courses/" + courseId + "/excel"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.startsWith("attachment")));
        }
    }
}
