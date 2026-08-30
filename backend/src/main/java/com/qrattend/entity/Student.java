package com.qrattend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a student enrolled in a specific course.
 * A student is uniquely identified by (course_id, roll_number).
 */
@Entity
@Table(name = "students", uniqueConstraints = {
        @UniqueConstraint(name = "uk_student_course_roll", columnNames = {"course_id", "roll_number"})
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

    @Column(nullable = false, length = 100)
    private String fullName;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
