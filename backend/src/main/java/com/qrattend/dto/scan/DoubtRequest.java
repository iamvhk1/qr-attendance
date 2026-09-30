package com.qrattend.dto.scan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/student/doubt}.
 * No student identity is stored — fully anonymous by design.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoubtRequest {

    @NotBlank(message = "Doubt text cannot be empty")
    @Size(max = 1000, message = "Doubt text cannot exceed 1000 characters")
    private String text;
}
