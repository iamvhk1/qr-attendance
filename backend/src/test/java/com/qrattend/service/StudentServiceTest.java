package com.qrattend.service;

import com.qrattend.dto.student.RosterSyncReport;
import com.qrattend.dto.student.StudentRequest;
import com.qrattend.dto.student.StudentResponse;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.entity.Student;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private CourseService courseService;
    @InjectMocks private StudentService studentService;

    private UUID profId;
    private UUID courseId;
    private Course course;

    @BeforeEach
    void setUp() {
        profId = UUID.randomUUID();
        courseId = UUID.randomUUID();
        Professor professor = Professor.builder().id(profId).fullName("Dr. Test").email("test@iitm.ac.in").build();
        course = Course.builder()
                .id(courseId)
                .professor(professor)
                .name("OS")
                .code("CS5013")
                .students(new ArrayList<>())
                .sessions(new ArrayList<>())
                .build();
    }

    private Student buildStudent(String roll, String name) {
        return Student.builder()
                .id(UUID.randomUUID())
                .course(course)
                .rollNumber(roll)
                .fullName(name)
                .createdAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("listStudents")
    class ListStudents {
        @Test
        void returnsStudentsForCourse() {
            when(courseService.getOwnedCourse(courseId, profId)).thenReturn(course);
            when(studentRepository.findByCourseId(courseId)).thenReturn(
                    List.of(buildStudent("CS24B001", "Alice"), buildStudent("CS24B002", "Bob")));

            List<StudentResponse> result = studentService.listStudents(courseId, profId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(StudentResponse::getRollNumber)
                    .containsExactly("CS24B001", "CS24B002");
        }
    }

    @Nested
    @DisplayName("addStudent")
    class AddStudent {
        @Test
        void addsSuccessfully() {
            when(courseService.getOwnedCourse(courseId, profId)).thenReturn(course);
            when(studentRepository.existsByCourseIdAndRollNumber(courseId, "CS24B001")).thenReturn(false);
            when(studentRepository.save(any(Student.class))).thenAnswer(inv -> {
                Student s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setCreatedAt(Instant.now());
                return s;
            });

            StudentRequest req = StudentRequest.builder().rollNumber("CS24B001").fullName("Alice").build();
            StudentResponse result = studentService.addStudent(courseId, profId, req);

            assertThat(result.getRollNumber()).isEqualTo("CS24B001");
            assertThat(result.getFullName()).isEqualTo("Alice");
            verify(studentRepository).save(any(Student.class));
        }

        @Test
        void throwsDuplicateForExistingRoll() {
            when(courseService.getOwnedCourse(courseId, profId)).thenReturn(course);
            when(studentRepository.existsByCourseIdAndRollNumber(courseId, "CS24B001")).thenReturn(true);

            StudentRequest req = StudentRequest.builder().rollNumber("CS24B001").fullName("Alice").build();

            assertThatThrownBy(() -> studentService.addStudent(courseId, profId, req))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("CS24B001");
        }
    }

    @Nested
    @DisplayName("deleteStudent")
    class DeleteStudent {
        @Test
        void deletesWhenOwned() {
            Student student = buildStudent("CS24B001", "Alice");
            when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));

            studentService.deleteStudent(student.getId(), profId);

            verify(studentRepository).delete(student);
        }

        @Test
        void throwsForbiddenWhenNotOwned() {
            Student student = buildStudent("CS24B001", "Alice");
            when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));

            UUID otherProf = UUID.randomUUID();
            assertThatThrownBy(() -> studentService.deleteStudent(student.getId(), otherProf))
                    .isInstanceOf(ForbiddenException.class);

            verify(studentRepository, never()).delete(any());
        }

        @Test
        void throwsNotFoundWhenMissing() {
            UUID missingId = UUID.randomUUID();
            when(studentRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> studentService.deleteStudent(missingId, profId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("importStudents (roster sync)")
    class ImportStudents {
        @Test
        void syncsRosterCorrectly() throws Exception {
            // Existing in DB: Alice (CS24B001), Bob (CS24B002), Charlie (CS24B003)
            Student alice = buildStudent("CS24B001", "Alice");
            Student bob = buildStudent("CS24B002", "Bob");
            Student charlie = buildStudent("CS24B003", "Charlie");

            when(courseService.getOwnedCourse(courseId, profId)).thenReturn(course);
            when(studentRepository.findByCourseId(courseId)).thenReturn(List.of(alice, bob, charlie));
            when(studentRepository.save(any(Student.class))).thenAnswer(inv -> {
                Student s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            // Create a real Excel file: Alice (CS24B001) + Dave (CS24B004)
            // Bob and Charlie are NOT in the Excel → should be removed
            org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Students");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("roll_number");
            header.createCell(1).setCellValue("full_name");
            sheet.createRow(1).createCell(0).setCellValue("CS24B001");
            sheet.getRow(1).createCell(1).setCellValue("Alice");
            sheet.createRow(2).createCell(0).setCellValue("CS24B004");
            sheet.getRow(2).createCell(1).setCellValue("Dave");

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            workbook.write(out);
            workbook.close();
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(out.toByteArray());

            RosterSyncReport report = studentService.importStudents(courseId, profId, in);

            assertThat(report.getAdded()).containsExactly("CS24B004");
            assertThat(report.getRemoved()).containsExactlyInAnyOrder("CS24B002", "CS24B003");
            assertThat(report.getUnchanged()).isEqualTo(1); // Alice
            assertThat(report.getTotalAfterSync()).isEqualTo(2); // Alice + Dave
            verify(studentRepository, times(1)).save(any(Student.class)); // Only Dave added
            verify(studentRepository, times(2)).delete(any(Student.class)); // Bob + Charlie deleted
        }
    }
}
