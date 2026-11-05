package com.qrattend.service;

import com.qrattend.dto.auth.LoginRequest;
import com.qrattend.dto.auth.LoginResponse;
import com.qrattend.dto.auth.RegisterRequest;
import com.qrattend.dto.auth.RegisterResponse;
import com.qrattend.entity.Professor;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.EmailNotAllowedException;
import com.qrattend.exception.InvalidCredentialsException;
import com.qrattend.exception.InvalidOtpException;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private ProfessorRepository professorRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailOtpService emailOtpService;

    @InjectMocks private AuthService authService;

    // ── Register ──────────────────────────────────────────────────

    @Test
    void register_ValidRequest_VerifiesCodeThenSavesProfessor() {
        RegisterRequest request = new RegisterRequest("prof@cse.iitm.ac.in", "123456", "password", "Test Name");
        given(professorRepository.existsByEmailIgnoreCase("prof@cse.iitm.ac.in")).willReturn(false);
        given(passwordEncoder.encode("password")).willReturn("hashed-password");
        Professor saved = Professor.builder().id(UUID.randomUUID())
                .fullName("Test Name").email("prof@cse.iitm.ac.in").passwordHash("hashed-password").build();
        given(professorRepository.save(any(Professor.class))).willReturn(saved);

        RegisterResponse response = authService.register(request);

        assertThat(response.professorId()).isEqualTo(saved.getId());
        assertThat(response.email()).isEqualTo("prof@cse.iitm.ac.in");
        assertThat(response.fullName()).isEqualTo("Test Name");

        InOrder order = inOrder(emailOtpService, professorRepository);
        order.verify(emailOtpService).assertAllowed("prof@cse.iitm.ac.in");
        order.verify(professorRepository).existsByEmailIgnoreCase("prof@cse.iitm.ac.in");
        order.verify(emailOtpService).verifyAndConsume("prof@cse.iitm.ac.in", "123456");
        order.verify(professorRepository).save(any(Professor.class));
    }

    @Test
    void register_NormalisesEmailAndTrimsName() {
        RegisterRequest request = new RegisterRequest("  Prof@IITM.ac.in ", "123456", "password", "  Test Name ");
        given(professorRepository.existsByEmailIgnoreCase("prof@iitm.ac.in")).willReturn(false);
        given(passwordEncoder.encode("password")).willReturn("hashed");
        given(professorRepository.save(any(Professor.class))).willAnswer(inv -> inv.getArgument(0));

        authService.register(request);

        ArgumentCaptor<Professor> captor = ArgumentCaptor.forClass(Professor.class);
        verify(professorRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("prof@iitm.ac.in");
        assertThat(captor.getValue().getFullName()).isEqualTo("Test Name");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed");
        verify(emailOtpService).verifyAndConsume("prof@iitm.ac.in", "123456");
    }

    @Test
    void register_DisallowedDomain_RejectedBeforeAnythingElse() {
        RegisterRequest request = new RegisterRequest("student@smail.iitm.ac.in", "123456", "password", "Name");
        willThrow(new EmailNotAllowedException("no")).given(emailOtpService).assertAllowed("student@smail.iitm.ac.in");

        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(EmailNotAllowedException.class);

        verifyNoInteractions(professorRepository);
        verify(emailOtpService, never()).verifyAndConsume(any(), any());
    }

    @Test
    void register_DuplicateEmail_RejectedWithoutUsingUpTheCode() {
        RegisterRequest request = new RegisterRequest("prof@iitm.ac.in", "123456", "password", "Name");
        given(professorRepository.existsByEmailIgnoreCase("prof@iitm.ac.in")).willReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already exists");

        verify(emailOtpService, never()).verifyAndConsume(any(), any());
        verify(professorRepository, never()).save(any());
    }

    @Test
    void register_WrongCode_NoAccountCreated() {
        RegisterRequest request = new RegisterRequest("prof@iitm.ac.in", "000000", "password", "Name");
        given(professorRepository.existsByEmailIgnoreCase("prof@iitm.ac.in")).willReturn(false);
        willThrow(new InvalidOtpException("Incorrect code. 4 attempts left."))
                .given(emailOtpService).verifyAndConsume("prof@iitm.ac.in", "000000");

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessageContaining("Incorrect code");

        verify(professorRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    // ── Login ─────────────────────────────────────────────────────

    @Test
    void login_ValidCredentials_ReturnsLoginResponse() {
        LoginRequest request = new LoginRequest("test@test.com", "password");
        UUID profId = UUID.randomUUID();
        Professor professor = Professor.builder().id(profId).email("test@test.com")
                .fullName("Test Name").passwordHash("hashed-password").build();

        given(professorRepository.findByEmail("test@test.com")).willReturn(Optional.of(professor));
        given(passwordEncoder.matches("password", "hashed-password")).willReturn(true);
        given(jwtUtil.generateLoginToken(profId, "test@test.com")).willReturn("valid-token");

        LoginResponse response = authService.login(request);

        assertThat(response.token()).isEqualTo("valid-token");
        assertThat(response.professorId()).isEqualTo(profId);
        assertThat(response.email()).isEqualTo("test@test.com");
        assertThat(response.fullName()).isEqualTo("Test Name");
    }

    @Test
    void login_DifferentCase_FallsBackToCaseInsensitiveLookup() {
        LoginRequest request = new LoginRequest("Prof@IITM.ac.in", "password");
        UUID profId = UUID.randomUUID();
        Professor professor = Professor.builder().id(profId).email("prof@iitm.ac.in")
                .fullName("Prof").passwordHash("hash").build();

        given(professorRepository.findByEmail("Prof@IITM.ac.in")).willReturn(Optional.empty());
        given(professorRepository.findByEmailIgnoreCase("Prof@IITM.ac.in")).willReturn(Optional.of(professor));
        given(passwordEncoder.matches("password", "hash")).willReturn(true);
        given(jwtUtil.generateLoginToken(profId, "prof@iitm.ac.in")).willReturn("token");

        assertThat(authService.login(request).token()).isEqualTo("token");
    }

    @Test
    void login_UnknownEmail_ThrowsInvalidCredentialsException() {
        LoginRequest request = new LoginRequest("unknown@test.com", "password");
        given(professorRepository.findByEmail("unknown@test.com")).willReturn(Optional.empty());
        given(professorRepository.findByEmailIgnoreCase("unknown@test.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_WrongPassword_ThrowsInvalidCredentialsException() {
        LoginRequest request = new LoginRequest("test@test.com", "wrong-password");
        Professor professor = Professor.builder().email("test@test.com").passwordHash("hashed-password").build();

        given(professorRepository.findByEmail("test@test.com")).willReturn(Optional.of(professor));
        given(passwordEncoder.matches("wrong-password", "hashed-password")).willReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }
}
