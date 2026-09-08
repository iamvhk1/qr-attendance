package com.qrattend.dto.session;

import com.qrattend.entity.QrSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a QR attendance session.
 *
 * <p>{@code status} is a computed string derived from the entity's
 * {@code isClosed()} method — it is never stored in the database.</p>
 *
 * <p>Status values:</p>
 * <ul>
 *   <li>{@code "LIVE"} — session window is still open; QR can be generated</li>
 *   <li>{@code "CLOSED"} — session is closed (timer expired or professor closed it)</li>
 * </ul>
 */
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class SessionResponse {

    private UUID id;
    private UUID courseId;
    private UUID professorId;
    private Instant createdAt;
    private Instant expiresAt;
    private int extendedCount;

    /** Null while the session is live; set when closed by professor or timer expiry. */
    private Instant closedAt;

    /** "LIVE" or "CLOSED" — computed, not stored. */
    private String status;

    /**
     * Maps a {@link QrSession} entity to a {@link SessionResponse} DTO.
     * The {@code status} field is derived from {@link QrSession#isClosed()}.
     */
    public static SessionResponse fromEntity(QrSession session) {
        return SessionResponse.builder()
                .id(session.getId())
                .courseId(session.getCourse().getId())
                .professorId(session.getProfessor().getId())
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .extendedCount(session.getExtendedCount())
                .closedAt(session.getClosedAt())
                .status(session.isClosed() ? "CLOSED" : "LIVE")
                .build();
    }
}
