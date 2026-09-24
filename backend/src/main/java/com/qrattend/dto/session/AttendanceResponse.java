package com.qrattend.dto.session;

import com.qrattend.entity.Attendance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a single attendance record inside a session.
 *
 * <p>Returned by {@code GET /api/sessions/{id}/attendance} so the professor
 * dashboard can show a live list of who has scanned and their current status.</p>
 *
 * <p>Note: {@code currentNonce} is intentionally excluded — it is a server-side
 * anti-cheat secret and must never be sent to the professor's browser.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceResponse {

    private UUID id;
    private String rollNumber;
    private String studentName;

    /**
     * Current presence status: {@code "PENDING"}, {@code "CONFIRMED"},
     * or {@code "INVALIDATED"}.
     */
    private String status;

    /** True if the professor manually added this record (not via QR scan). */
    private boolean manuallyAdded;

    /** Optional reason given for a manual override. */
    private String overrideReason;

    /** Heartbeat coverage ratio (0.0–1.0). Null until the session closes. */
    private Float heartbeatCoverage;

    /** When the student's scan was first accepted. */
    private Instant markedAt;

    /**
     * Maps an {@link Attendance} entity to an {@link AttendanceResponse} DTO.
     */
    public static AttendanceResponse fromEntity(Attendance a) {
        return AttendanceResponse.builder()
                .id(a.getId())
                .rollNumber(a.getRollNumber())
                .studentName(a.getStudentName())
                .status(a.getStatus().name())
                .manuallyAdded(Boolean.TRUE.equals(a.getManuallyAdded()))
                .overrideReason(a.getOverrideReason())
                .heartbeatCoverage(a.getHeartbeatCoverage())
                .markedAt(a.getMarkedAt())
                .build();
    }
}
