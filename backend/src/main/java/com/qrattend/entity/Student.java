package com.qrattend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Represents a student enrolled in a specific course.
 * A student is uniquely identified by the course and the roll number, ignoring letter case:
 * "CS24B075" and "cs24b075" are the same student. The database enforces this through
 * {@link #rollKey} (see the second unique constraint below).
 */
@Entity
@Table(name = "students", uniqueConstraints = {
        @UniqueConstraint(name = "uk_student_course_roll", columnNames = {"course_id", "roll_number"}),
        // Roll numbers are unique per course ignoring letter case (roll_key is the upper-cased copy)
        @UniqueConstraint(name = "uk_student_course_roll_key", columnNames = {"course_id", "roll_key"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "roll_number", nullable = false, length = 20)
    private String rollNumber;

    /**
     * Upper-cased, trimmed copy of {@link #rollNumber}, filled in automatically on save. It exists
     * only so the database can enforce "one student per roll number, ignoring case". It is null
     * for rows that predate it and share a roll number with another student of the same course
     * (see {@code StudentRollKeyBackfill}).
     */
    @Column(name = "roll_key", length = 20)
    private String rollKey;

    @Column(nullable = false, length = 100)
    private String fullName;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    /** Keeps {@link #rollKey} in step with {@link #rollNumber} on every insert and update. */
    @PrePersist
    @PreUpdate
    void syncRollKey() {
        rollKey = rollNumber == null ? null : rollNumber.trim().toUpperCase(Locale.ROOT);
    }
}
