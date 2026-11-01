package com.qrattend.entity;

import lombok.*;

import java.time.Instant;

/**
 * Model representing a single heartbeat ping in an isolated session database.
 *
 * <p>Separated from the primary relational database into per-session databases:
 * no session_id column is required, and heartbeats are indexed solely by roll_number.</p>
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Heartbeat {

    private Long id;

    private String rollNumber;

    private Instant receivedAt;
}
