package com.qrattend.dto.course;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a new course.
 */
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class CourseRequest {

    @NotBlank(message = "Course name is required")
    @Size(max = 100, message = "Course name must be at most 100 characters")
    private String name;

    @NotBlank(message = "Course code is required")
    @Size(max = 20, message = "Course code must be at most 20 characters")
    private String code;

    @Size(max = 20, message = "Semester must be at most 20 characters")
    private String semester;
}
