package com.qrattend;

import com.qrattend.entity.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Factory for creating pre-configured test entities.
 * Keeps test methods clean by centralising entity construction.
 */
public final class TestDataFactory {

    private TestDataFactory() { /* utility class */ }

    // ── Professor ──────────────────────────────────────────────

    public static Professor professor() {
        return Professor.builder()
                .fullName("Dr. Boby George")
                .email("boby@iitm.ac.in")
                .passwordHash("$2a$10$hashedpassword")
                .build();
    }

    public static Professor professor(String email) {
        return Professor.builder()
                .fullName("Professor " + email.split("@")[0])
                .email(email)
                .passwordHash("$2a$10$hashedpassword")
                .build();
    }

    // ── Course ─────────────────────────────────────────────────

    public static Course course(Professor professor) {
        return Course.builder()
                .professor(professor)
                .name("Operating Systems")
                .code("CS5013")
                .semester("Sem 5 - 2026")
                .build();
    }

    public static Course course(Professor professor, String code) {
        return Course.builder()
                .professor(professor)
                .name("Course " + code)
                .code(code)
                .semester("Sem 5 - 2026")
                .build();
    }

    // ── Student ────────────────────────────────────────────────

    public static Student student(Course course, String rollNumber, String fullName) {
        return Student.builder()
                .course(course)
                .rollNumber(rollNumber)
                .fullName(fullName)
                .build();
    }

    public static Student student(Course course) {
        return student(course, "CS24B075", "Harsha Karthikeya");
    }

    // ── QrSession ──────────────────────────────────────────────

    /** A session that expires 2 minutes from now (live). */
    public static QrSession liveSession(Course course, Professor professor) {
        return QrSession.builder()
                .course(course)
                .professor(professor)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .build();
    }

    /** A session that expired 5 minutes ago (naturally expired). */
    public static QrSession expiredSession(Course course, Professor professor) {
        return QrSession.builder()
                .course(course)
                .professor(professor)
                .expiresAt(Instant.now().minus(5, ChronoUnit.MINUTES))
                .build();
    }

    /** A session that was explicitly closed by the professor. */
    public static QrSession closedSession(Course course, Professor professor) {
        return QrSession.builder()
                .course(course)
                .professor(professor)
                .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES)) // hasn't expired yet
                .closedAt(Instant.now().minus(1, ChronoUnit.MINUTES))  // but was closed early
                .build();
    }

    // ── Attendance ─────────────────────────────────────────────

    /** A confirmed QR-scanned attendance entry. */
    public static Attendance confirmedAttendance(QrSession session, String rollNumber) {
        return Attendance.builder()
                .session(session)
                .rollNumber(rollNumber)
                .studentName("Student " + rollNumber)
                .status("CONFIRMED")
                .manuallyAdded(false)
                .build();
    }

    /** A pending attendance entry (awaiting heartbeat verification). */
    public static Attendance pendingAttendance(QrSession session, String rollNumber) {
        return Attendance.builder()
                .session(session)
                .rollNumber(rollNumber)
                .studentName("Student " + rollNumber)
                .status("PENDING")
                .manuallyAdded(false)
                .presenceStart(Instant.now())
                .presenceEnd(Instant.now().plus(90, ChronoUnit.SECONDS))
                .build();
    }

    /** A manual override attendance entry. */
    public static Attendance manualAttendance(QrSession session, String rollNumber, Professor professor) {
        return Attendance.builder()
                .session(session)
                .rollNumber(rollNumber)
                .studentName("Student " + rollNumber)
                .status("CONFIRMED")
                .manuallyAdded(true)
                .addedByProfessor(professor)
                .overrideReason("Phone battery died")
                .build();
    }

    // ── Heartbeat ──────────────────────────────────────────────

    public static Heartbeat heartbeat(QrSession session, String rollNumber) {
        return Heartbeat.builder()
                .session(session)
                .rollNumber(rollNumber)
                .build();
    }

    // ── Doubt ──────────────────────────────────────────────────

    public static Doubt doubt(QrSession session, String question) {
        return Doubt.builder()
                .session(session)
                .question(question)
                .build();
    }
}
