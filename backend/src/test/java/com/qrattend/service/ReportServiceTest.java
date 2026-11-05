package com.qrattend.service;

import com.qrattend.dto.report.CourseAttendanceReport;
import com.qrattend.dto.report.CourseAttendanceReport.SessionColumn;
import com.qrattend.dto.report.CourseAttendanceReport.StudentRow;
import com.qrattend.entity.*;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.repository.AttendanceRepository;
import com.qrattend.repository.CourseRepository;
import com.qrattend.repository.QrSessionRepository;
import com.qrattend.repository.StudentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the course-wide attendance report in {@link ReportService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService — course attendance report")
class ReportServiceTest {

    @Mock private QrSessionRepository sessionRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private StudentRepository studentRepository;

    @InjectMocks private ReportService reportService;

    private UUID profId;
    private UUID courseId;
    private Course course;
    private final Instant base = Instant.parse("2026-11-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        profId = UUID.randomUUID();
        courseId = UUID.randomUUID();
        Professor professor = Professor.builder().id(profId).email("p@test.com").fullName("Prof").build();
        course = Course.builder().id(courseId).professor(professor).name("Operating Systems").code("CS5013").build();
    }

    // ── Fixture helpers ──────────────────────────────────────────

    private Student student(String roll, String name) {
        return Student.builder().id(UUID.randomUUID()).course(course).rollNumber(roll).fullName(name).build();
    }

    /** A session created {@code dayOffset} days after {@code base}; closed or live. */
    private QrSession session(int dayOffset, boolean closed) {
        Instant created = base.plus(dayOffset, ChronoUnit.DAYS);
        return QrSession.builder()
                .id(UUID.randomUUID())
                .course(course)
                .createdAt(created)
                .expiresAt(closed ? created.plus(2, ChronoUnit.MINUTES) : Instant.now().plus(5, ChronoUnit.MINUTES))
                .closedAt(closed ? created.plus(2, ChronoUnit.MINUTES) : null)
                .build();
    }

    private Attendance att(QrSession s, String roll, AttendanceStatus status) {
        return Attendance.builder().id(UUID.randomUUID()).session(s).rollNumber(roll).status(status).build();
    }

    /** Stubs the repositories. Sessions are passed oldest-first; the repository returns newest-first. */
    private void stub(List<Student> students, List<QrSession> sessionsOldestFirst, List<Attendance> records) {
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
        when(studentRepository.findByCourseId(courseId)).thenReturn(students);
        List<QrSession> newestFirst = new ArrayList<>(sessionsOldestFirst);
        java.util.Collections.reverse(newestFirst);
        when(sessionRepository.findByCourseIdOrderByCreatedAtDesc(courseId)).thenReturn(newestFirst);
        when(attendanceRepository.findAllByCourseId(courseId)).thenReturn(records);
    }

    private StudentRow row(CourseAttendanceReport r, String roll) {
        return r.getStudents().stream().filter(s -> s.getRollNumber().equals(roll)).findFirst().orElseThrow();
    }

    // ══════════════════════════════════════════════════════════════
    //  Full matrix
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Full matrix — 3 students, 2 closed sessions + 1 live")
    class FullMatrix {

        private QrSession s1, s2, live;
        private CourseAttendanceReport report;

        @BeforeEach
        void build() {
            s1 = session(0, true);
            s2 = session(1, true);
            live = session(2, false);
            stub(
                    List.of(student("CS24B003", "Carol"), student("CS24B001", "Alice"), student("CS24B002", "Bob")),
                    List.of(s1, s2, live),
                    List.of(
                            att(s1, "CS24B001", AttendanceStatus.CONFIRMED),
                            att(s1, "CS24B002", AttendanceStatus.INVALIDATED),
                            att(s2, "CS24B001", AttendanceStatus.CONFIRMED),
                            att(s2, "CS24B002", AttendanceStatus.CONFIRMED),
                            att(s2, "CS24B003", AttendanceStatus.PENDING),
                            att(s2, "ZZ99Z999", AttendanceStatus.CONFIRMED),   // not enrolled
                            att(live, "CS24B003", AttendanceStatus.CONFIRMED)  // live → not counted
                    ));
            report = reportService.buildCourseReport(courseId, profId);
        }

        @Test
        @DisplayName("Course metadata is included")
        void courseMetadata() {
            assertThat(report.getCourseId()).isEqualTo(courseId);
            assertThat(report.getCourseName()).isEqualTo("Operating Systems");
            assertThat(report.getCourseCode()).isEqualTo("CS5013");
        }

        @Test
        @DisplayName("Session columns are oldest first; live session is listed but not counted")
        void sessionColumns() {
            assertThat(report.getSessions()).extracting(SessionColumn::getId)
                    .containsExactly(s1.getId(), s2.getId(), live.getId());
            assertThat(report.getSessions()).extracting(SessionColumn::getStatus)
                    .containsExactly("CLOSED", "CLOSED", "LIVE");
            assertThat(report.getSessions()).extracting(SessionColumn::isCounted)
                    .containsExactly(true, true, false);
            assertThat(report.getSessions().get(0).getCreatedAt()).isEqualTo(s1.getCreatedAt());
        }

        @Test
        @DisplayName("Per-session confirmed count ignores roll numbers that are not enrolled")
        void confirmedCounts() {
            assertThat(report.getSessions()).extracting(SessionColumn::getConfirmedCount)
                    .containsExactly(1, 2, 1);
        }

        @Test
        @DisplayName("Students are sorted by roll number")
        void studentsSorted() {
            assertThat(report.getStudents()).extracting(StudentRow::getRollNumber)
                    .containsExactly("CS24B001", "CS24B002", "CS24B003");
            assertThat(report.getStudents()).extracting(StudentRow::getFullName)
                    .containsExactly("Alice", "Bob", "Carol");
        }

        @Test
        @DisplayName("Not-enrolled roll numbers do not get a row")
        void notEnrolledIgnored() {
            assertThat(report.getStudents()).extracting(StudentRow::getRollNumber).doesNotContain("ZZ99Z999");
        }

        @Test
        @DisplayName("Cells follow session order; missing records are ABSENT")
        void cells() {
            assertThat(row(report, "CS24B001").getCells()).containsExactly("CONFIRMED", "CONFIRMED", "ABSENT");
            assertThat(row(report, "CS24B002").getCells()).containsExactly("INVALIDATED", "CONFIRMED", "ABSENT");
            assertThat(row(report, "CS24B003").getCells()).containsExactly("ABSENT", "PENDING", "CONFIRMED");
        }

        @Test
        @DisplayName("Only CONFIRMED in a closed session counts as attended")
        void attendedAndTotal() {
            assertThat(row(report, "CS24B001").getAttended()).isEqualTo(2);
            assertThat(row(report, "CS24B002").getAttended()).isEqualTo(1);
            // PENDING in a closed session and CONFIRMED in a live session both don't count
            assertThat(row(report, "CS24B003").getAttended()).isZero();
            assertThat(report.getStudents()).extracting(StudentRow::getTotal).containsOnly(2);
        }

        @Test
        @DisplayName("Percentages and low-attendance flags")
        void percentages() {
            assertThat(row(report, "CS24B001").getPercentage()).isEqualTo(100.0);
            assertThat(row(report, "CS24B001").isBelowThreshold()).isFalse();
            assertThat(row(report, "CS24B002").getPercentage()).isEqualTo(50.0);
            assertThat(row(report, "CS24B002").isBelowThreshold()).isTrue();
            assertThat(row(report, "CS24B003").getPercentage()).isEqualTo(0.0);
            assertThat(row(report, "CS24B003").isBelowThreshold()).isTrue();
        }

        @Test
        @DisplayName("Summary totals")
        void summary() {
            CourseAttendanceReport.Summary s = report.getSummary();
            assertThat(s.getStudentCount()).isEqualTo(3);
            assertThat(s.getCountedSessions()).isEqualTo(2);
            assertThat(s.getLiveSessions()).isEqualTo(1);
            assertThat(s.getAveragePercentage()).isEqualTo(50.0);
            assertThat(s.getBelowThresholdCount()).isEqualTo(2);
            assertThat(s.getThreshold()).isEqualTo(75.0);
        }

        @Test
        @DisplayName("Loads all attendance in one course-wide query, never per session")
        void singleAttendanceQuery() {
            verify(attendanceRepository, times(1)).findAllByCourseId(courseId);
            verify(attendanceRepository, never()).findBySessionId(any());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Edge cases
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Percentages are rounded to one decimal (1/3 → 33.3, 2/3 → 66.7)")
        void rounding() {
            QrSession a = session(0, true), b = session(1, true), c = session(2, true);
            stub(List.of(student("CS24B001", "Alice"), student("CS24B002", "Bob")),
                    List.of(a, b, c),
                    List.of(att(a, "CS24B001", AttendanceStatus.CONFIRMED),
                            att(a, "CS24B002", AttendanceStatus.CONFIRMED),
                            att(b, "CS24B002", AttendanceStatus.CONFIRMED)));

            CourseAttendanceReport r = reportService.buildCourseReport(courseId, profId);

            assertThat(row(r, "CS24B001").getPercentage()).isEqualTo(33.3);
            assertThat(row(r, "CS24B002").getPercentage()).isEqualTo(66.7);
            assertThat(r.getSummary().getAveragePercentage()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("Exactly 75% is not flagged as low attendance")
        void thresholdBoundary() {
            QrSession a = session(0, true), b = session(1, true), c = session(2, true), d = session(3, true);
            stub(List.of(student("CS24B001", "Alice")),
                    List.of(a, b, c, d),
                    List.of(att(a, "CS24B001", AttendanceStatus.CONFIRMED),
                            att(b, "CS24B001", AttendanceStatus.CONFIRMED),
                            att(c, "CS24B001", AttendanceStatus.CONFIRMED)));

            StudentRow alice = reportService.buildCourseReport(courseId, profId).getStudents().get(0);

            assertThat(alice.getPercentage()).isEqualTo(75.0);
            assertThat(alice.isBelowThreshold()).isFalse();
        }

        @Test
        @DisplayName("No sessions yet → percentage is null (not 0%) and nobody is flagged")
        void noSessions() {
            stub(List.of(student("CS24B001", "Alice")), List.of(), List.of());

            CourseAttendanceReport r = reportService.buildCourseReport(courseId, profId);

            StudentRow alice = r.getStudents().get(0);
            assertThat(alice.getTotal()).isZero();
            assertThat(alice.getPercentage()).isNull();
            assertThat(alice.isBelowThreshold()).isFalse();
            assertThat(alice.getCells()).isEmpty();
            assertThat(r.getSummary().getAveragePercentage()).isNull();
            assertThat(r.getSummary().getBelowThresholdCount()).isZero();
        }

        @Test
        @DisplayName("Only a live session → nothing counted yet, cells still shown")
        void onlyLiveSession() {
            QrSession live = session(0, false);
            stub(List.of(student("CS24B001", "Alice")), List.of(live),
                    List.of(att(live, "CS24B001", AttendanceStatus.PENDING)));

            CourseAttendanceReport r = reportService.buildCourseReport(courseId, profId);

            assertThat(r.getSummary().getCountedSessions()).isZero();
            assertThat(r.getSummary().getLiveSessions()).isEqualTo(1);
            assertThat(r.getStudents().get(0).getPercentage()).isNull();
            assertThat(r.getStudents().get(0).getCells()).containsExactly("PENDING");
        }

        @Test
        @DisplayName("No students → empty rows, null average")
        void noStudents() {
            QrSession a = session(0, true);
            stub(List.of(), List.of(a), List.of(att(a, "CS24B001", AttendanceStatus.CONFIRMED)));

            CourseAttendanceReport r = reportService.buildCourseReport(courseId, profId);

            assertThat(r.getStudents()).isEmpty();
            assertThat(r.getSummary().getStudentCount()).isZero();
            assertThat(r.getSummary().getAveragePercentage()).isNull();
            assertThat(r.getSessions().get(0).getConfirmedCount()).isZero();
        }

        @Test
        @DisplayName("An expired (not explicitly closed) session counts as closed")
        void expiredSessionCounts() {
            Instant created = base;
            QrSession expired = QrSession.builder().id(UUID.randomUUID()).course(course)
                    .createdAt(created).expiresAt(created.plus(2, ChronoUnit.MINUTES)).closedAt(null).build();
            stub(List.of(student("CS24B001", "Alice")), List.of(expired),
                    List.of(att(expired, "CS24B001", AttendanceStatus.CONFIRMED)));

            CourseAttendanceReport r = reportService.buildCourseReport(courseId, profId);

            assertThat(r.getSessions().get(0).isCounted()).isTrue();
            assertThat(r.getSessions().get(0).getStatus()).isEqualTo("CLOSED");
            assertThat(r.getStudents().get(0).getPercentage()).isEqualTo(100.0);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Access control
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Access control")
    class AccessControl {

        @Test
        @DisplayName("Throws ResourceNotFoundException for a missing course; no data is read")
        void notFound() {
            when(courseRepository.findById(courseId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reportService.buildCourseReport(courseId, profId))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(studentRepository, sessionRepository, attendanceRepository);
        }

        @Test
        @DisplayName("Throws ForbiddenException for another professor's course; no data is read")
        void forbidden() {
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            assertThatThrownBy(() -> reportService.buildCourseReport(courseId, UUID.randomUUID()))
                    .isInstanceOf(ForbiddenException.class);
            verifyNoInteractions(studentRepository, sessionRepository, attendanceRepository);
        }

        @Test
        @DisplayName("Excel export enforces the same ownership check")
        void excelForbidden() {
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            assertThatThrownBy(() -> reportService.generateCourseExcel(courseId, UUID.randomUUID()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Excel matches the report
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Course Excel is rendered from the same report")
    class ExcelMatchesReport {

        @Test
        @DisplayName("Headers, per-student numbers, cells and summary match buildCourseReport")
        void excelMatches() throws Exception {
            QrSession s1 = session(0, true), live = session(1, false);
            stub(List.of(student("CS24B002", "Bob"), student("CS24B001", "Alice")),
                    List.of(s1, live),
                    List.of(att(s1, "CS24B001", AttendanceStatus.CONFIRMED),
                            att(s1, "CS24B002", AttendanceStatus.INVALIDATED)));

            byte[] bytes = reportService.generateCourseExcel(courseId, profId);

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                Sheet sheet = wb.getSheetAt(0);
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                        .contains("Operating Systems").contains("CS5013");
                assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
                        .contains("Counted sessions: 1").contains("1 live session");

                Row header = sheet.getRow(2);
                assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Roll No");
                assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Attendance %");
                assertThat(header.getCell(5).getStringCellValue()).doesNotContain("(live)");
                assertThat(header.getCell(6).getStringCellValue()).endsWith("(live)");

                Row alice = sheet.getRow(3);   // sorted by roll number
                assertThat(alice.getCell(0).getStringCellValue()).isEqualTo("CS24B001");
                assertThat(alice.getCell(2).getNumericCellValue()).isEqualTo(1.0);   // total
                assertThat(alice.getCell(3).getNumericCellValue()).isEqualTo(1.0);   // attended
                assertThat(alice.getCell(4).getStringCellValue()).isEqualTo("100.0%");
                assertThat(alice.getCell(5).getStringCellValue()).isEqualTo("CONFIRMED");
                assertThat(alice.getCell(6).getStringCellValue()).isEqualTo("Absent");

                Row bob = sheet.getRow(4);
                assertThat(bob.getCell(0).getStringCellValue()).isEqualTo("CS24B002");
                assertThat(bob.getCell(3).getNumericCellValue()).isEqualTo(0.0);
                assertThat(bob.getCell(4).getStringCellValue()).isEqualTo("0.0%");
                assertThat(bob.getCell(5).getStringCellValue()).isEqualTo("INVALIDATED");

                assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Average attendance:");
                assertThat(sheet.getRow(6).getCell(1).getStringCellValue()).isEqualTo("50.0%");
                assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("Below 75%:");
                assertThat(sheet.getRow(7).getCell(1).getNumericCellValue()).isEqualTo(1.0);
            }
        }

        @Test
        @DisplayName("With no counted sessions the % column shows a dash, not 0%")
        void excelDashWhenNothingCounted() throws Exception {
            stub(List.of(student("CS24B001", "Alice")), List.of(), List.of());

            byte[] bytes = reportService.generateCourseExcel(courseId, profId);

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertThat(wb.getSheetAt(0).getRow(3).getCell(4).getStringCellValue()).isEqualTo("—");
            }
        }
    }
}
