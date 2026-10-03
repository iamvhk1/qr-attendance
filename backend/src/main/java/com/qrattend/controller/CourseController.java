package com.qrattend.controller;

import com.qrattend.dto.course.CourseRequest;
import com.qrattend.dto.course.CourseResponse;
import com.qrattend.dto.session.SessionResponse;
import com.qrattend.service.CourseService;
import com.qrattend.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for Course CRUD operations.
 * All endpoints require a valid professor JWT (ROLE_PROFESSOR).
 */
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final SessionService sessionService;

    /**
     * GET /api/courses — List all courses for the logged-in professor.
     */
    @GetMapping
    public ResponseEntity<List<CourseResponse>> listCourses() {
        UUID professorId = getAuthenticatedProfessorId();
        return ResponseEntity.ok(courseService.listCourses(professorId));
    }

    /**
     * POST /api/courses — Create a new course.
     */
    @PostMapping
    public ResponseEntity<CourseResponse> createCourse(@Valid @RequestBody CourseRequest request) {
        UUID professorId = getAuthenticatedProfessorId();
        CourseResponse created = courseService.createCourse(professorId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * GET /api/courses/{id} — Get a single course (ownership-checked).
     */
    @GetMapping("/{id}")
    public ResponseEntity<CourseResponse> getCourse(@PathVariable UUID id) {
        UUID professorId = getAuthenticatedProfessorId();
        return ResponseEntity.ok(courseService.getCourse(id, professorId));
    }

    /**
     * DELETE /api/courses/{id} — Delete a course and all its children.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCourse(@PathVariable UUID id) {
        UUID professorId = getAuthenticatedProfessorId();
        courseService.deleteCourse(id, professorId);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/courses/{id}/sessions — List all sessions for a course.
     */
    @GetMapping("/{id}/sessions")
    public ResponseEntity<List<SessionResponse>> listCourseSessions(@PathVariable UUID id) {
        UUID professorId = getAuthenticatedProfessorId();
        return ResponseEntity.ok(sessionService.listSessionsByCourse(id, professorId));
    }

    // ── Helper ──────────────────────────────────────────────

    /**
     * Extracts the professor UUID from the SecurityContext.
     * This reads directly from SecurityContextHolder, which works reliably
     * in both production (set by JwtAuthenticationFilter) and tests
     * (set manually via @BeforeEach).
     */
    private UUID getAuthenticatedProfessorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
