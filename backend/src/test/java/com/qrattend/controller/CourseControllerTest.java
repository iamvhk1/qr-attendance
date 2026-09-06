package com.qrattend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.dto.course.CourseRequest;
import com.qrattend.dto.course.CourseResponse;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.GlobalExceptionHandler;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.security.JwtAuthenticationEntryPoint;
import com.qrattend.security.JwtAuthenticationFilter;
import com.qrattend.security.JwtUtil;
import com.qrattend.service.CourseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer tests for {@link CourseController}.
 * Security filters disabled — we test only controller + exception-handler logic.
 * SecurityContext is populated manually in @BeforeEach since addFilters=false
 * prevents the SecurityContextHolderFilter from running.
 */
@WebMvcTest(CourseController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class CourseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private CourseService courseService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private final UUID profId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                profId, null, List.of(new SimpleGrantedAuthority("ROLE_PROFESSOR")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listCourses_returnsOk() throws Exception {
        CourseResponse r = CourseResponse.builder()
                .id(UUID.randomUUID()).name("OS").code("CS5013")
                .semester("Jan 2026").studentCount(0).createdAt(Instant.now()).build();
        given(courseService.listCourses(profId)).willReturn(List.of(r));

        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CS5013"));
    }

    @Test
    void createCourse_returns201() throws Exception {
        CourseResponse r = CourseResponse.builder()
                .id(UUID.randomUUID()).name("OS").code("CS5013")
                .semester("Jan 2026").studentCount(0).createdAt(Instant.now()).build();
        given(courseService.createCourse(eq(profId), any(CourseRequest.class))).willReturn(r);

        CourseRequest req = CourseRequest.builder().name("OS").code("CS5013").semester("Jan 2026").build();

        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CS5013"));
    }

    @Test
    void createCourse_validationFails_returns400() throws Exception {
        CourseRequest req = CourseRequest.builder().name("").code("").build();

        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCourse_returnsOk() throws Exception {
        UUID courseId = UUID.randomUUID();
        CourseResponse r = CourseResponse.builder()
                .id(courseId).name("OS").code("CS5013")
                .semester("Jan 2026").studentCount(5).createdAt(Instant.now()).build();
        given(courseService.getCourse(courseId, profId)).willReturn(r);

        mockMvc.perform(get("/api/courses/" + courseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentCount").value(5));
    }

    @Test
    void getCourse_notFound_returns404() throws Exception {
        UUID courseId = UUID.randomUUID();
        given(courseService.getCourse(courseId, profId))
                .willThrow(new ResourceNotFoundException("Course not found"));

        mockMvc.perform(get("/api/courses/" + courseId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCourse_forbidden_returns403() throws Exception {
        UUID courseId = UUID.randomUUID();
        given(courseService.getCourse(courseId, profId))
                .willThrow(new ForbiddenException("You do not own this course"));

        mockMvc.perform(get("/api/courses/" + courseId))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteCourse_returns204() throws Exception {
        UUID courseId = UUID.randomUUID();
        doNothing().when(courseService).deleteCourse(courseId, profId);

        mockMvc.perform(delete("/api/courses/" + courseId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteCourse_notOwned_returns403() throws Exception {
        UUID courseId = UUID.randomUUID();
        doThrow(new ForbiddenException("Not your course"))
                .when(courseService).deleteCourse(courseId, profId);

        mockMvc.perform(delete("/api/courses/" + courseId))
                .andExpect(status().isForbidden());
    }
}
