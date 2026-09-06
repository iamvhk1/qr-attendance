package com.qrattend.service;

import com.qrattend.dto.course.CourseRequest;
import com.qrattend.dto.course.CourseResponse;
import com.qrattend.entity.Course;
import com.qrattend.entity.Professor;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.repository.CourseRepository;
import com.qrattend.repository.ProfessorRepository;
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
class CourseServiceTest {

    @Mock private CourseRepository courseRepository;
    @Mock private ProfessorRepository professorRepository;
    @InjectMocks private CourseService courseService;

    private UUID profId;
    private UUID otherProfId;
    private Professor professor;

    @BeforeEach
    void setUp() {
        profId = UUID.randomUUID();
        otherProfId = UUID.randomUUID();
        professor = Professor.builder().id(profId).fullName("Dr. Test").email("test@iitm.ac.in").build();
    }

    private Course buildCourse(String name, String code) {
        return Course.builder()
                .id(UUID.randomUUID())
                .professor(professor)
                .name(name)
                .code(code)
                .semester("Jan 2026")
                .createdAt(Instant.now())
                .students(new ArrayList<>())
                .sessions(new ArrayList<>())
                .build();
    }

    @Nested
    @DisplayName("listCourses")
    class ListCourses {
        @Test
        void returnsAllCoursesForProfessor() {
            Course c1 = buildCourse("Operating Systems", "CS5013");
            Course c2 = buildCourse("Data Structures", "CS3001");
            when(courseRepository.findByProfessorId(profId)).thenReturn(List.of(c1, c2));

            List<CourseResponse> result = courseService.listCourses(profId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(CourseResponse::getCode).containsExactly("CS5013", "CS3001");
        }

        @Test
        void returnsEmptyListIfNoCourses() {
            when(courseRepository.findByProfessorId(profId)).thenReturn(List.of());

            List<CourseResponse> result = courseService.listCourses(profId);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("createCourse")
    class CreateCourse {
        @Test
        void createsSuccessfully() {
            when(professorRepository.findById(profId)).thenReturn(Optional.of(professor));
            when(courseRepository.save(any(Course.class))).thenAnswer(inv -> {
                Course c = inv.getArgument(0);
                c.setId(UUID.randomUUID());
                c.setCreatedAt(Instant.now());
                return c;
            });

            CourseRequest request = CourseRequest.builder()
                    .name("Operating Systems")
                    .code("CS5013")
                    .semester("Jan 2026")
                    .build();

            CourseResponse result = courseService.createCourse(profId, request);

            assertThat(result.getName()).isEqualTo("Operating Systems");
            assertThat(result.getCode()).isEqualTo("CS5013");
            assertThat(result.getId()).isNotNull();
            verify(courseRepository).save(any(Course.class));
        }

        @Test
        void throwsWhenProfessorNotFound() {
            when(professorRepository.findById(profId)).thenReturn(Optional.empty());

            CourseRequest request = CourseRequest.builder().name("X").code("Y").build();

            assertThatThrownBy(() -> courseService.createCourse(profId, request))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getCourse / ownership")
    class GetCourse {
        @Test
        void returnsWhenOwned() {
            Course course = buildCourse("OS", "CS5013");
            when(courseRepository.findById(course.getId())).thenReturn(Optional.of(course));

            CourseResponse result = courseService.getCourse(course.getId(), profId);

            assertThat(result.getCode()).isEqualTo("CS5013");
        }

        @Test
        void throwsForbiddenWhenNotOwned() {
            Course course = buildCourse("OS", "CS5013");
            when(courseRepository.findById(course.getId())).thenReturn(Optional.of(course));

            assertThatThrownBy(() -> courseService.getCourse(course.getId(), otherProfId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("do not own");
        }

        @Test
        void throwsNotFoundWhenMissing() {
            UUID missingId = UUID.randomUUID();
            when(courseRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> courseService.getCourse(missingId, profId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("deleteCourse")
    class DeleteCourse {
        @Test
        void deletesWhenOwned() {
            Course course = buildCourse("OS", "CS5013");
            when(courseRepository.findById(course.getId())).thenReturn(Optional.of(course));

            courseService.deleteCourse(course.getId(), profId);

            verify(courseRepository).delete(course);
        }

        @Test
        void throwsForbiddenWhenNotOwned() {
            Course course = buildCourse("OS", "CS5013");
            when(courseRepository.findById(course.getId())).thenReturn(Optional.of(course));

            assertThatThrownBy(() -> courseService.deleteCourse(course.getId(), otherProfId))
                    .isInstanceOf(ForbiddenException.class);

            verify(courseRepository, never()).delete(any());
        }
    }
}
