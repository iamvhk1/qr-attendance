package com.qrattend.service;

import com.qrattend.dto.auth.LoginRequest;
import com.qrattend.dto.auth.LoginResponse;
import com.qrattend.dto.auth.RegisterRequest;
import com.qrattend.dto.auth.RegisterResponse;
import com.qrattend.entity.Professor;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.InvalidCredentialsException;
import com.qrattend.exception.InvalidInviteCodeException;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private ProfessorRepository professorRepository;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    // ── Register ──────────────────────────────────────────────────

    @Test
    void register_ValidRequest_SavesProfessorAndReturnsResponse() {
        // Arrange
        RegisterRequest request = new RegisterRequest("valid-invite", "test@test.com", "password", "Test Name");
        
        given(jwtUtil.isInviteToken("valid-invite")).willReturn(true);
        given(professorRepository.existsByEmail("test@test.com")).willReturn(false);
        given(passwordEncoder.encode("password")).willReturn("hashed-password");

        Professor savedProfessor = Professor.builder()
                .id(UUID.randomUUID())
                .fullName("Test Name")
                .email("test@test.com")
                .passwordHash("hashed-password")
                .build();
                
        given(professorRepository.save(any(Professor.class))).willReturn(savedProfessor);

        // Act
        RegisterResponse response = authService.register(request);

        // Assert
        assertThat(response.professorId()).isEqualTo(savedProfessor.getId());
        assertThat(response.email()).isEqualTo("test@test.com");
        assertThat(response.fullName()).isEqualTo("Test Name");

        ArgumentCaptor<Professor> professorCaptor = ArgumentCaptor.forClass(Professor.class);
        verify(professorRepository).save(professorCaptor.capture());
        Professor captured = professorCaptor.getValue();
        assertThat(captured.getEmail()).isEqualTo("test@test.com");
        assertThat(captured.getFullName()).isEqualTo("Test Name");
        assertThat(captured.getPasswordHash()).isEqualTo("hashed-password");
    }

    @Test
    void register_InvalidInvite_ThrowsInvalidInviteCodeException() {
        RegisterRequest request = new RegisterRequest("invalid-invite", "test@test.com", "password", "Test Name");
        given(jwtUtil.isInviteToken("invalid-invite")).willReturn(false);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("Invalid or expired invite code");
    }

    @Test
    void register_DuplicateEmail_ThrowsDuplicateResourceException() {
        RegisterRequest request = new RegisterRequest("valid-invite", "test@test.com", "password", "Test Name");
        given(jwtUtil.isInviteToken("valid-invite")).willReturn(true);
        given(professorRepository.existsByEmail("test@test.com")).willReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Email is already registered");
    }

    // ── Login ─────────────────────────────────────────────────────

    @Test
    void login_ValidCredentials_ReturnsLoginResponse() {
        // Arrange
        LoginRequest request = new LoginRequest("test@test.com", "password");
        UUID profId = UUID.randomUUID();
        Professor professor = Professor.builder()
                .id(profId)
                .email("test@test.com")
                .fullName("Test Name")
                .passwordHash("hashed-password")
                .build();

        given(professorRepository.findByEmail("test@test.com")).willReturn(Optional.of(professor));
        given(passwordEncoder.matches("password", "hashed-password")).willReturn(true);
        given(jwtUtil.generateLoginToken(profId, "test@test.com")).willReturn("valid-token");

        // Act
        LoginResponse response = authService.login(request);

        // Assert
        assertThat(response.token()).isEqualTo("valid-token");
        assertThat(response.professorId()).isEqualTo(profId);
        assertThat(response.email()).isEqualTo("test@test.com");
        assertThat(response.fullName()).isEqualTo("Test Name");
    }

    @Test
    void login_UnknownEmail_ThrowsInvalidCredentialsException() {
        LoginRequest request = new LoginRequest("unknown@test.com", "password");
        given(professorRepository.findByEmail("unknown@test.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_WrongPassword_ThrowsInvalidCredentialsException() {
        LoginRequest request = new LoginRequest("test@test.com", "wrong-password");
        Professor professor = Professor.builder()
                .email("test@test.com")
                .passwordHash("hashed-password")
                .build();

        given(professorRepository.findByEmail("test@test.com")).willReturn(Optional.of(professor));
        given(passwordEncoder.matches("wrong-password", "hashed-password")).willReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }
}
