package com.qrattend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A one-time code sent to an email address to prove the registrant owns it.
 *
 * <p>At most one row per email: requesting a new code replaces the previous one.
 * Only a hash of the code is stored. The row is deleted when the code is used
 * for a successful registration, and expired rows are purged by a scheduled job.</p>
 */
@Entity
@Table(name = "email_otps")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class EmailOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Normalised (trimmed, lower-case) email address. */
    @Column(nullable = false, unique = true, length = 100)
    private String email;

    /** BCrypt hash of the 6-digit code. The plain code is never stored. */
    @Column(nullable = false, length = 100)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Wrong guesses against the current code. */
    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    /** When the latest code was sent (drives the resend cooldown). */
    @Column(nullable = false)
    private Instant lastSentAt;

    /** Start of the current hourly sending window for this email. */
    @Column(nullable = false)
    private Instant windowStart;

    /** Codes sent to this email within the current window. */
    @Column(nullable = false)
    @Builder.Default
    private Integer sendCount = 0;
}
