package com.qrattend.dto.session;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for creating a new QR attendance session.
 *
 * <p>{@code durationSeconds} is optional. When omitted (null), the server
 * defaults to the value of {@code app.session.default-duration-seconds} (120s).</p>
 */
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class SessionRequest {

    @NotNull(message = "courseId is required")
    private UUID courseId;

    /**
     * How long (in seconds) the session window stays open.
     * Defaults to 120s server-side if not provided.
     * Must be at least 1 second.
     */
    @Min(value = 1, message = "durationSeconds must be at least 1")
    private Integer durationSeconds;
}
