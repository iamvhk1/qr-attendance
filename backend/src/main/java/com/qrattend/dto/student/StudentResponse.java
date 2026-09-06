package com.qrattend.dto.student;

import com.qrattend.entity.Student;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for student data returned to the client.
 */
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class StudentResponse {

    private UUID id;
    private String rollNumber;
    private String fullName;
    private Instant createdAt;

    /**
     * Maps a Student entity to a StudentResponse DTO.
     */
    public static StudentResponse fromEntity(Student student) {
        return StudentResponse.builder()
                .id(student.getId())
                .rollNumber(student.getRollNumber())
                .fullName(student.getFullName())
                .createdAt(student.getCreatedAt())
                .build();
    }
}
