package com.qrattend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single attendance-taking session.
 * Created when the professor clicks "Start Attendance" — expires after a configurable duration.
 */
@Entity
@Table(name = "qr_sessions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class QrSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professor_id", nullable = false)
    private Professor professor;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    /** When this session expires (created_at + duration). Extended via PATCH. */
    @Column(nullable = false)
    private Instant expiresAt;

    /** How many times the professor clicked "Extend +15s". */
    @Column(nullable = false)
    @Builder.Default
    private Integer extendedCount = 0;

    /** Null while the session is live. Set when professor closes or timer expires. */
    private Instant closedAt;

    // ----- Relationships (cascade delete: deleting a session removes attendance + heartbeats + doubts) -----

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Attendance> attendances = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Heartbeat> heartbeats = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Doubt> doubts = new ArrayList<>();

    // ----- Convenience methods -----

    /** Returns true if the session has been explicitly closed or has expired. */
    public boolean isClosed() {
        return closedAt != null || Instant.now().isAfter(expiresAt);
    }
}
