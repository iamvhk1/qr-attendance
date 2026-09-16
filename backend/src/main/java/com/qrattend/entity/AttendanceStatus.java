package com.qrattend.entity;

/**
 * Lifecycle states for a single attendance record.
 *
 * <p>State machine:</p>
 * <ul>
 *   <li>{@link #PENDING} — QR scanned; waiting for heartbeat coverage computation.</li>
 *   <li>{@link #CONFIRMED} — Coverage ≥ 80% (or manual professor override). Counts as present.</li>
 *   <li>{@link #INVALIDATED} — Coverage < 80%, or webdriver detected, or manually removed. Treated as absent.</li>
 * </ul>
 *
 * <p>Stored as a {@code VARCHAR} in the database via {@code @Enumerated(EnumType.STRING)}
 * so that DB rows remain human-readable and portable.</p>
 */
public enum AttendanceStatus {

    /** QR scan accepted; awaiting heartbeat coverage computation at session close. */
    PENDING,

    /**
     * Student was confirmed present.
     * Set by the coverage scheduler when heartbeat ratio ≥ 80%,
     * or set immediately by a professor manual override.
     */
    CONFIRMED,

    /**
     * Student failed the presence check.
     * Set by the coverage scheduler when heartbeat ratio < 80%,
     * by the heartbeat endpoint when a webdriver is detected,
     * or by a professor manual removal.
     */
    INVALIDATED
}
