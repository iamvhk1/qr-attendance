package com.qrattend.dto.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code PATCH /api/sessions/{id}/extend}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtendRequest {

    /**
     * Number of seconds to add to the session's current expiry.
     * Defaults to 60 if not provided. Capped at 600 (10 minutes) per extension.
     */
    @Min(value = 1, message = "additionalSeconds must be at least 1")
    @Max(value = 600, message = "additionalSeconds cannot exceed 600 (10 minutes)")
    @Builder.Default
    private int additionalSeconds = 60;
}
