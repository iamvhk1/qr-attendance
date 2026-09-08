package com.qrattend.service;

import com.qrattend.dto.course.CourseRequest;
import com.qrattend.dto.course.CourseResponse;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.repository.CourseRepository;
import com.qrattend.repository.ProfessorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for Course CRUD operations.
 * All operations enforce professor ownership — a professor can only
 * access or modify their own courses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourseService {

    private final CourseRepository courseRepository;
    private final ProfessorRepository professorRepository;

    /**
     * Lists all courses owned by the given professor.
     */
    @Transactional(readOnly = true)
    public List<CourseResponse> listCourses(UUID professorId) {
        return courseRepository.findByProfessorId(professorId).stream()
                .map(CourseResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Creates a new course owned by the given professor.
     */
    @Transactional
    public CourseResponse createCourse(UUID professorId, CourseRequest request) {
        Professor professor = professorRepository.findById(professorId)
                .orElseThrow(() -> new ResourceNotFoundException("Professor not found"));

        Course course = Course.builder()
                .professor(professor)
                .name(request.getName().trim())
                .code(request.getCode().trim())
                .semester(request.getSemester() != null ? request.getSemester().trim() : null)
                .build();

        Course saved = courseRepository.save(course);
        log.info("Course created: {} ({}) by professor {}", saved.getName(), saved.getCode(), professorId);
        return CourseResponse.fromEntity(saved);
    }

    /**
     * Fetches a single course, verifying the professor owns it.
     */
    @Transactional(readOnly = true)
    public CourseResponse getCourse(UUID courseId, UUID professorId) {
        Course course = getOwnedCourse(courseId, professorId);
        return CourseResponse.fromEntity(course);
    }

    /**
     * Deletes a course (cascade deletes students and sessions).
     */
    @Transactional
    public void deleteCourse(UUID courseId, UUID professorId) {
        Course course = getOwnedCourse(courseId, professorId);
        courseRepository.delete(course);
        log.info("Course deleted: {} ({}) by professor {}", course.getName(), course.getCode(), professorId);
    }

    // ── Internal helper ──────────────────────────────────────

    /**
     * Fetches a course by ID and verifies that the given professor owns it.
     *
     * @throws ResourceNotFoundException if the course does not exist
     * @throws ForbiddenException if the professor does not own the course
     */
    public Course getOwnedCourse(UUID courseId, UUID professorId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course not found"));

        if (!course.getProfessor().getId().equals(professorId)) {
            throw new ForbiddenException("You do not own this course");
        }
        return course;
    }
}
