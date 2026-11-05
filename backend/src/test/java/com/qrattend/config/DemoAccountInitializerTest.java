package com.qrattend.config;

import com.qrattend.entity.Professor;
import com.qrattend.mail.EmailSender;
import com.qrattend.repository.EmailOtpRepository;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.service.EmailOtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DemoAccountInitializer")
class DemoAccountInitializerTest {

    private static final String DEMO_EMAIL = "demo@qr-attendance.demo";
    private static final String DEMO_PASSWORD = "Correct-Horse-42";

    private ProfessorRepository professorRepository;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private EmailOtpService emailOtpService;

    @BeforeEach
    void setUp() {
        professorRepository = mock(ProfessorRepository.class);
        when(professorRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        // Real domain rules (iitm.ac.in allowed, smail blocked); its repositories are unused here
        emailOtpService = new EmailOtpService(mock(EmailOtpRepository.class), professorRepository, encoder,
                mock(EmailSender.class), List.of("iitm.ac.in"), List.of("smail.iitm.ac.in"), 600, 60, 5, 5, 250);
    }

    private DemoAccountInitializer init(String email, String password) {
        return new DemoAccountInitializer(professorRepository, encoder, emailOtpService, email, password, "Demo Professor");
    }

    private Professor saved() {
        ArgumentCaptor<Professor> captor = ArgumentCaptor.forClass(Professor.class);
        verify(professorRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Not configured → does nothing")
    void disabledByDefault() {
        init("", "").run(null);
        init("", DEMO_PASSWORD).run(null);
        verifyNoInteractions(professorRepository);
    }

    @Test
    @DisplayName("Creates the account with a hashed password; email may be any non-IITM domain")
    void createsAccount() {
        init("  Demo@QR-Attendance.demo ", DEMO_PASSWORD).run(null);

        Professor p = saved();
        assertThat(p.getEmail()).isEqualTo(DEMO_EMAIL);
        assertThat(p.getFullName()).isEqualTo("Demo Professor");
        assertThat(p.getPasswordHash()).isNotEqualTo(DEMO_PASSWORD);
        assertThat(encoder.matches(DEMO_PASSWORD, p.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Existing account: password updated when the variable changes")
    void updatesPassword() {
        Professor existing = Professor.builder().email(DEMO_EMAIL).fullName("Demo").passwordHash(encoder.encode("Old-Password-99")).build();
        when(professorRepository.findByEmailIgnoreCase(DEMO_EMAIL)).thenReturn(Optional.of(existing));

        init(DEMO_EMAIL, DEMO_PASSWORD).run(null);

        assertThat(encoder.matches(DEMO_PASSWORD, saved().getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Existing account with the same password: left alone")
    void unchangedPasswordNotRewritten() {
        Professor existing = Professor.builder().email(DEMO_EMAIL).fullName("Demo").passwordHash(encoder.encode(DEMO_PASSWORD)).build();
        when(professorRepository.findByEmailIgnoreCase(DEMO_EMAIL)).thenReturn(Optional.of(existing));

        init(DEMO_EMAIL, DEMO_PASSWORD).run(null);

        verify(professorRepository, never()).save(any());
    }

    @Test
    @DisplayName("Password cleared → existing account locked with an unknown password")
    void locksAccount() {
        Professor existing = Professor.builder().email(DEMO_EMAIL).fullName("Demo").passwordHash(encoder.encode(DEMO_PASSWORD)).build();
        when(professorRepository.findByEmailIgnoreCase(DEMO_EMAIL)).thenReturn(Optional.of(existing));

        init(DEMO_EMAIL, "").run(null);

        Professor p = saved();
        assertThat(encoder.matches(DEMO_PASSWORD, p.getPasswordHash())).isFalse();
        assertThat(p.getEmail()).isEqualTo(DEMO_EMAIL);
    }

    @Test
    @DisplayName("Password cleared and no account → nothing created")
    void lockWithoutAccountDoesNothing() {
        init(DEMO_EMAIL, "").run(null);
        verify(professorRepository, never()).save(any());
    }

    @Test
    @DisplayName("Refuses IITM addresses, so a real professor's password can never be reset")
    void refusesRegistrationDomains() {
        for (String email : new String[]{"someone@iitm.ac.in", "someone@ee.iitm.ac.in"}) {
            init(email, DEMO_PASSWORD).run(null);
            init(email, "").run(null);
        }
        verify(professorRepository, never()).findByEmailIgnoreCase(any());
        verify(professorRepository, never()).save(any());
    }

    @Test
    @DisplayName("Refuses passwords shorter than 12 characters")
    void refusesShortPassword() {
        init(DEMO_EMAIL, "short-pw").run(null);
        verify(professorRepository, never()).save(any());
    }

    @Test
    @DisplayName("Refuses a value that isn't an email address")
    void refusesNonEmail() {
        init("not-an-email", DEMO_PASSWORD).run(null);
        verifyNoInteractions(professorRepository);
    }
}
