package com.qrattend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.dto.student.StudentRequest;
import com.qrattend.dto.student.StudentResponse;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.GlobalExceptionHandler;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.security.JwtAuthenticationEntryPoint;
import com.qrattend.security.JwtAuthenticationFilter;
import com.qrattend.security.JwtUtil;
import com.qrattend.service.StudentService;
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
 * Controller-layer tests for {@link StudentController}.
 * Security filters disabled — we test only controller + exception-handler logic.
 * SecurityContext is populated manually in @BeforeEach.
 */
@WebMvcTest(StudentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class StudentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private StudentService studentService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private final UUID profId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

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
    void listStudents_returnsOk() throws Exception {
        StudentResponse s = StudentResponse.builder()
                .id(UUID.randomUUID()).rollNumber("CS24B001")
                .fullName("Alice").createdAt(Instant.now()).build();
        given(studentService.listStudents(courseId, profId)).willReturn(List.of(s));

        mockMvc.perform(get("/api/courses/" + courseId + "/students"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rollNumber").value("CS24B001"));
    }

    @Test
    void addStudent_returns201() throws Exception {
        StudentResponse s = StudentResponse.builder()
                .id(UUID.randomUUID()).rollNumber("CS24B001")
                .fullName("Alice").createdAt(Instant.now()).build();
        given(studentService.addStudent(eq(courseId), eq(profId), any(StudentRequest.class))).willReturn(s);

        StudentRequest req = StudentRequest.builder().rollNumber("CS24B001").fullName("Alice").build();

        mockMvc.perform(post("/api/courses/" + courseId + "/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rollNumber").value("CS24B001"));
    }

    @Test
    void addStudent_validationFails_returns400() throws Exception {
        StudentRequest req = StudentRequest.builder().rollNumber("").fullName("").build();

        mockMvc.perform(post("/api/courses/" + courseId + "/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addStudent_duplicate_returns409() throws Exception {
        given(studentService.addStudent(eq(courseId), eq(profId), any(StudentRequest.class)))
                .willThrow(new DuplicateResourceException("Duplicate roll number"));

        StudentRequest req = StudentRequest.builder().rollNumber("CS24B001").fullName("Alice").build();

        mockMvc.perform(post("/api/courses/" + courseId + "/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteStudent_returns204() throws Exception {
        UUID studentId = UUID.randomUUID();
        doNothing().when(studentService).deleteStudent(studentId, profId);

        mockMvc.perform(delete("/api/students/" + studentId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteStudent_notFound_returns404() throws Exception {
        UUID studentId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Student not found"))
                .when(studentService).deleteStudent(studentId, profId);

        mockMvc.perform(delete("/api/students/" + studentId))
                .andExpect(status().isNotFound());
    }
}
