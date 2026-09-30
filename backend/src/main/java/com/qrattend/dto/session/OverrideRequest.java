package com.qrattend.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code PATCH /api/sessions/attendance/{id}/override}.
 *
 * <p>Allows a professor to manually correct an attendance record's status.
 * Only {@code CONFIRMED} and {@code INVALIDATED} are valid override targets —
 * professors cannot reset a record back to {@code PENDING}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverrideRequest {

    /**
     * The new attendance status. Must be either {@code "CONFIRMED"} or {@code "INVALIDATED"}.
     */
    @NotBlank(message = "Status is required")
    @Pattern(
        regexp = "CONFIRMED|INVALIDATED",
        message = "Status must be CONFIRMED or INVALIDATED"
    )
    private String status;

    /** Optional reason the professor provides for the override. */
    private String reason;
}
