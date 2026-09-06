package com.qrattend.controller;

import com.qrattend.dto.student.RosterSyncReport;
import com.qrattend.dto.student.StudentRequest;
import com.qrattend.dto.student.StudentResponse;
import com.qrattend.service.StudentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for Student CRUD and Excel import operations.
 */
@RestController
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;

    /**
     * GET /api/courses/{courseId}/students — List all students in a course.
     */
    @GetMapping("/api/courses/{courseId}/students")
    public ResponseEntity<List<StudentResponse>> listStudents(@PathVariable UUID courseId) {
        UUID professorId = getAuthenticatedProfessorId();
        return ResponseEntity.ok(studentService.listStudents(courseId, professorId));
    }

    /**
     * POST /api/courses/{courseId}/students — Add a single student.
     */
    @PostMapping("/api/courses/{courseId}/students")
    public ResponseEntity<StudentResponse> addStudent(
            @PathVariable UUID courseId,
            @Valid @RequestBody StudentRequest request) {
        UUID professorId = getAuthenticatedProfessorId();
        StudentResponse created = studentService.addStudent(courseId, professorId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * DELETE /api/students/{studentId} — Remove a student.
     */
    @DeleteMapping("/api/students/{studentId}")
    public ResponseEntity<Void> deleteStudent(@PathVariable UUID studentId) {
        UUID professorId = getAuthenticatedProfessorId();
        studentService.deleteStudent(studentId, professorId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/courses/{courseId}/students/import — Import students from an Excel file.
     * Performs a full roster sync: adds new students, removes missing ones.
     */
    @PostMapping("/api/courses/{courseId}/students/import")
    public ResponseEntity<RosterSyncReport> importStudents(
            @PathVariable UUID courseId,
            @RequestParam("file") MultipartFile file) throws IOException {
        UUID professorId = getAuthenticatedProfessorId();
        RosterSyncReport report = studentService.importStudents(courseId, professorId, file.getInputStream());
        return ResponseEntity.ok(report);
    }

    // ── Helper ──────────────────────────────────────────────

    private UUID getAuthenticatedProfessorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
