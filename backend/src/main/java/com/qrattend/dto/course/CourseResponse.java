package com.qrattend.dto.course;

import com.qrattend.entity.Course;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for course data returned to the client.
 */
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class CourseResponse {

    private UUID id;
    private String name;
    private String code;
    private String semester;
    private int studentCount;
    private Instant createdAt;

    /**
     * Maps a Course entity to a CourseResponse DTO.
     */
    public static CourseResponse fromEntity(Course course) {
        return CourseResponse.builder()
                .id(course.getId())
                .name(course.getName())
                .code(course.getCode())
                .semester(course.getSemester())
                .studentCount(course.getStudents() != null ? course.getStudents().size() : 0)
                .createdAt(course.getCreatedAt())
                .build();
    }
}
