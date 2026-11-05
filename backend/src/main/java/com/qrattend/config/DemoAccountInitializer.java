package com.qrattend.config;

import com.qrattend.entity.Professor;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.service.EmailOtpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Optional demo login, managed entirely through environment variables, for showing the
 * app to people without an IIT Madras email. It bypasses email verification, so it can
 * only be created by whoever controls the server's environment.
 *
 * <ul>
 *   <li><b>Off</b> (default): {@code DEMO_ACCOUNT_EMAIL} not set — nothing happens.</li>
 *   <li><b>On</b>: {@code DEMO_ACCOUNT_EMAIL} and {@code DEMO_ACCOUNT_PASSWORD} set — on every
 *       startup the account is created if missing, and its password is set to the given value
 *       (so changing the variable changes the password).</li>
 *   <li><b>Locked</b>: {@code DEMO_ACCOUNT_EMAIL} set but {@code DEMO_ACCOUNT_PASSWORD} empty —
 *       the account's password is replaced with a random one nobody knows, so it can no longer
 *       log in. Its courses and sessions are kept.</li>
 * </ul>
 *
 * <p>The password is never logged.</p>
 */
@Component
@Slf4j
public class DemoAccountInitializer implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;

    private final ProfessorRepository professorRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailOtpService emailOtpService;
    private final String email;
    private final String password;
    private final String fullName;

    public DemoAccountInitializer(
            ProfessorRepository professorRepository,
            PasswordEncoder passwordEncoder,
            EmailOtpService emailOtpService,
            @Value("${app.demo.email:}") String email,
            @Value("${app.demo.password:}") String password,
            @Value("${app.demo.full-name:Demo Professor}") String fullName) {
        this.professorRepository = professorRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailOtpService = emailOtpService;
        this.email = EmailOtpService.normalise(email);
        this.password = password == null ? "" : password;
        this.fullName = fullName == null || fullName.isBlank() ? "Demo Professor" : fullName.trim();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isEmpty()) {
            return;   // demo account not configured
        }
        if (!email.contains("@")) {
            log.error("DEMO_ACCOUNT_EMAIL '{}' is not an email address — demo account not set up", email);
            return;
        }
        // Never manage an address that real professors register with: it could reset a
        // genuine account's password. Demo accounts must use some other domain.
        if (isRegistrationDomain(email)) {
            log.error("DEMO_ACCOUNT_EMAIL '{}' is at a registration domain — refusing to manage it as a "
                    + "demo account. Use a non-IITM address such as demo@qr-attendance.demo", email);
            return;
        }

        Professor existing = professorRepository.findByEmailIgnoreCase(email).orElse(null);

        if (password.isEmpty()) {
            if (existing != null) {
                existing.setPasswordHash(passwordEncoder.encode(UUID.randomUUID() + "-" + UUID.randomUUID()));
                professorRepository.save(existing);
                log.warn("Demo account {} locked (DEMO_ACCOUNT_PASSWORD is empty)", email);
            }
            return;
        }

        if (password.length() < MIN_PASSWORD_LENGTH) {
            log.error("DEMO_ACCOUNT_PASSWORD must be at least {} characters — demo account not set up",
                    MIN_PASSWORD_LENGTH);
            return;
        }

        if (existing == null) {
            professorRepository.save(Professor.builder()
                    .email(email)
                    .fullName(fullName)
                    .passwordHash(passwordEncoder.encode(password))
                    .build());
            log.info("Demo account created: {}", email);
        } else {
            if (!passwordEncoder.matches(password, existing.getPasswordHash())) {
                existing.setPasswordHash(passwordEncoder.encode(password));
                professorRepository.save(existing);
                log.info("Demo account {} password updated from DEMO_ACCOUNT_PASSWORD", email);
            } else {
                log.info("Demo account ready: {}", email);
            }
        }
    }

    /** True if real professors can register with this address (e.g. any @iitm.ac.in). */
    private boolean isRegistrationDomain(String address) {
        try {
            emailOtpService.assertAllowed(address);
            return true;
        } catch (com.qrattend.exception.EmailNotAllowedException e) {
            return false;
        }
    }
}
