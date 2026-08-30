package com.qrattend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an anonymous doubt posted by a student during or after an attendance session.
 * No student identity is stored — doubts are fully anonymous by design.
 */
@Entity
@Table(name = "doubts")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Doubt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private QrSession session;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant postedAt;
}
