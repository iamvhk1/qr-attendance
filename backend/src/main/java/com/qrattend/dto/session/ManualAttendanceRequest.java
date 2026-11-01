package com.qrattend.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for manually adding a student's attendance.
 *
 * <p>Used by {@code POST /api/sessions/{id}/attendance/manual}.
 * The professor supplies the roll number, and optionally a reason.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualAttendanceRequest {

    @NotBlank(message = "Roll number is required")
    @Size(max = 20, message = "Roll number must be at most 20 characters")
    private String rollNumber;

    @Size(max = 255, message = "Reason must be at most 255 characters")
    private String reason;
}
