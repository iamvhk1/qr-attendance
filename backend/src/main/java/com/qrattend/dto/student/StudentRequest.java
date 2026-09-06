package com.qrattend.dto.student;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for adding a student to a course.
 */
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class StudentRequest {

    @NotBlank(message = "Roll number is required")
    @Size(max = 20, message = "Roll number must be at most 20 characters")
    private String rollNumber;

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name must be at most 100 characters")
    private String fullName;
}
