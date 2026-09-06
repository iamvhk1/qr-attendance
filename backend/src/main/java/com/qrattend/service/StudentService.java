package com.qrattend.service;

import com.qrattend.dto.student.RosterSyncReport;
import com.qrattend.dto.student.StudentRequest;
import com.qrattend.dto.student.StudentResponse;
import com.qrattend.entity.Course;
import com.qrattend.entity.Student;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.repository.StudentRepository;
import com.qrattend.util.ExcelImportUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Business logic for Student CRUD and Excel roster sync operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentService {

    private final StudentRepository studentRepository;
    private final CourseService courseService;

    /**
     * Lists all students in a course (ownership-checked).
     */
    public List<StudentResponse> listStudents(UUID courseId, UUID professorId) {
        // Verify professor owns the course
        courseService.getOwnedCourse(courseId, professorId);

        return studentRepository.findByCourseId(courseId).stream()
                .map(StudentResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Adds a single student to a course (ownership-checked).
     *
     * @throws DuplicateResourceException if the roll number already exists in the course
     */
    @Transactional
    public StudentResponse addStudent(UUID courseId, UUID professorId, StudentRequest request) {
        Course course = courseService.getOwnedCourse(courseId, professorId);

        String rollNumber = request.getRollNumber().trim();
        String fullName = request.getFullName().trim();

        if (studentRepository.existsByCourseIdAndRollNumber(courseId, rollNumber)) {
            throw new DuplicateResourceException(
                    "Student with roll number '" + rollNumber + "' already exists in this course");
        }

        Student student = Student.builder()
                .course(course)
                .rollNumber(rollNumber)
                .fullName(fullName)
                .build();

        Student saved = studentRepository.save(student);
        log.info("Student added: {} ({}) to course {}", fullName, rollNumber, courseId);
        return StudentResponse.fromEntity(saved);
    }

    /**
     * Deletes a student by ID (verifies professor owns the student's course).
     */
    @Transactional
    public void deleteStudent(UUID studentId, UUID professorId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found"));

        // Verify professor owns the course this student belongs to
        if (!student.getCourse().getProfessor().getId().equals(professorId)) {
            throw new ForbiddenException("You do not own the course this student belongs to");
        }

        studentRepository.delete(student);
        log.info("Student deleted: {} ({}) from course {}",
                student.getFullName(), student.getRollNumber(), student.getCourse().getId());
    }

    /**
     * Imports students from an Excel file and syncs the roster.
     * <ul>
     *   <li>Students in Excel but NOT in DB → added</li>
     *   <li>Students in DB but NOT in Excel → removed</li>
     *   <li>Students in both → unchanged</li>
     * </ul>
     */
    @Transactional
    public RosterSyncReport importStudents(UUID courseId, UUID professorId, InputStream excelFile) {
        Course course = courseService.getOwnedCourse(courseId, professorId);

        // 1. Parse Excel
        List<StudentRequest> fromExcel = ExcelImportUtil.parseStudentExcel(excelFile);

        // 2. Fetch existing students
        List<Student> existingStudents = studentRepository.findByCourseId(courseId);

        // 3. Build maps for comparison
        Map<String, Student> existingByRoll = existingStudents.stream()
                .collect(Collectors.toMap(Student::getRollNumber, s -> s));

        Set<String> excelRollNumbers = fromExcel.stream()
                .map(StudentRequest::getRollNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 4. ADDITIONS — in Excel but not in DB
        List<String> added = new ArrayList<>();
        for (StudentRequest req : fromExcel) {
            if (!existingByRoll.containsKey(req.getRollNumber())) {
                Student student = Student.builder()
                        .course(course)
                        .rollNumber(req.getRollNumber())
                        .fullName(req.getFullName())
                        .build();
                studentRepository.save(student);
                added.add(req.getRollNumber());
            }
        }

        // 5. DELETIONS — in DB but not in Excel
        List<String> removed = new ArrayList<>();
        for (Student existing : existingStudents) {
            if (!excelRollNumbers.contains(existing.getRollNumber())) {
                studentRepository.delete(existing);
                removed.add(existing.getRollNumber());
            }
        }

        // 6. UNCHANGED count
        int unchanged = existingStudents.size() - removed.size();

        int totalAfterSync = unchanged + added.size();

        log.info("Roster sync for course {}: +{} added, -{} removed, {} unchanged, {} total",
                courseId, added.size(), removed.size(), unchanged, totalAfterSync);

        return RosterSyncReport.builder()
                .added(added)
                .removed(removed)
                .unchanged(unchanged)
                .totalAfterSync(totalAfterSync)
                .build();
    }
}
