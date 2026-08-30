package com.qrattend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single attendance record — one student in one session.
 * Created when a student scans the QR and submits, or when a professor manually overrides.
 *
 * Status lifecycle:
 *   PENDING → CONFIRMED  (heartbeat coverage ≥ 80%)
 *   PENDING → INVALIDATED (heartbeat coverage < 80%)
 *   CONFIRMED (immediate — for manual overrides, no dwell time required)
 */
@Entity
@Table(name = "attendance", uniqueConstraints = {
        @UniqueConstraint(name = "uk_attendance_session_roll", columnNames = {"session_id", "roll_number"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private QrSession session;

    @Column(name = "roll_number", nullable = false, length = 20)
    private String rollNumber;

    @Column(length = 100)
    private String studentName;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant markedAt;

    // ----- Manual override fields -----

    /** True if the professor explicitly added this student (not via QR scan). */
    @Column(nullable = false)
    @Builder.Default
    private Boolean manuallyAdded = false;

    /** The professor who manually added this entry (null for QR scans). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_professor")
    private Professor addedByProfessor;

    /** Optional reason the professor gives for the manual override. */
    @Column(length = 255)
    private String overrideReason;

    // ----- Presence verification fields -----

    /** PENDING = awaiting heartbeat confirmation, CONFIRMED = attendance counts, INVALIDATED = treated as absent. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "CONFIRMED";

    /** When the dwell timer started (set on initial scan submission). */
    private Instant presenceStart;

    /** When the dwell timer should end (= session expiry or fixed window). */
    private Instant presenceEnd;

    /** Computed coverage ratio (0.0 to 1.0) after session closes. Null until computed. */
    private Float heartbeatCoverage;
}
