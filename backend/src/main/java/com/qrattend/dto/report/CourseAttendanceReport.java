package com.qrattend.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Course-wide attendance matrix: every enrolled student against every session.
 *
 * <p>Returned as JSON by {@code GET /api/reports/courses/{id}} for the in-browser
 * attendance view, and also the single source the course Excel report is rendered
 * from — so the web page and the downloaded file always show the same numbers.</p>
 *
 * <h2>Counting rules</h2>
 * <ul>
 *   <li>Only <b>closed</b> sessions (ended or expired) count toward a student's total.
 *       Live sessions are still listed as columns (with {@code counted = false}) but do
 *       not affect anyone's percentage.</li>
 *   <li>A session counts as attended only when the record is {@code CONFIRMED}.</li>
 *   <li>Only students on the current roster appear; scans from roll numbers that are
 *       not enrolled are ignored.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseAttendanceReport {

    /** Below this attendance percentage a student is flagged as low attendance. */
    public static final double LOW_ATTENDANCE_THRESHOLD = 75.0;

    /** Cell value for an enrolled student with no attendance record in a session. */
    public static final String ABSENT = "ABSENT";

    private UUID courseId;
    private String courseName;
    private String courseCode;

    /** Session columns, oldest first. */
    private List<SessionColumn> sessions;

    /** One row per enrolled student, sorted by roll number. */
    private List<StudentRow> students;

    private Summary summary;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SessionColumn {
        private UUID id;
        private Instant createdAt;
        /** {@code "LIVE"} or {@code "CLOSED"} — same values as {@code SessionResponse.status}. */
        private String status;
        /** False for live sessions, which are shown but not counted toward totals. */
        private boolean counted;
        /** Number of enrolled students CONFIRMED in this session. */
        private int confirmedCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StudentRow {
        private String rollNumber;
        private String fullName;
        /** Counted sessions this student was CONFIRMED in. */
        private int attended;
        /** Number of counted (closed) sessions. */
        private int total;
        /** {@code attended / total * 100}, rounded to one decimal; null when {@code total == 0}. */
        private Double percentage;
        /** True when {@code percentage} is below {@link #LOW_ATTENDANCE_THRESHOLD}. */
        private boolean belowThreshold;
        /**
         * One entry per session, in the same order as {@code sessions}:
         * {@code CONFIRMED}, {@code INVALIDATED}, {@code PENDING} or {@code ABSENT}.
         */
        private List<String> cells;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {
        private int studentCount;
        /** Closed sessions — the denominator for every student's percentage. */
        private int countedSessions;
        /** Live sessions, listed but not counted. */
        private int liveSessions;
        /** Mean of the students' percentages; null when there are no students or no counted sessions. */
        private Double averagePercentage;
        private int belowThresholdCount;
        private double threshold;
    }
}
