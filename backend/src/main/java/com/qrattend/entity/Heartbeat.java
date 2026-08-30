package com.qrattend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a single heartbeat ping from a student's browser during presence verification.
 * Many heartbeats per student per session (one every ~5 seconds).
 * Used to compute heartbeat coverage when the session closes.
 */
@Entity
@Table(name = "heartbeats", indexes = {
        @Index(name = "idx_heartbeat_session_roll", columnList = "session_id, roll_number")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Heartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private QrSession session;

    @Column(name = "roll_number", nullable = false, length = 20)
    private String rollNumber;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant receivedAt;
}
