package com.qrattend.service;

import com.qrattend.dto.auth.LoginRequest;
import com.qrattend.dto.auth.LoginResponse;
import com.qrattend.dto.auth.RegisterRequest;
import com.qrattend.dto.auth.RegisterResponse;
import com.qrattend.entity.Professor;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.InvalidCredentialsException;
import com.qrattend.exception.EmailNotAllowedException;
import com.qrattend.exception.InvalidOtpException;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles professor registration and login.
 *
 * <p><b>Register flow:</b> check the email domain → check email uniqueness →
 * verify the emailed code ({@link EmailOtpService}) → bcrypt password → save professor.</p>
 *
 * <p><b>Login flow:</b> find by email → verify bcrypt password →
 * generate login JWT.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final ProfessorRepository professorRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final EmailOtpService emailOtpService;

    /**
     * Registers a new professor account.
     *
     * @param request the registration payload (email, emailed code, password, name)
     * @return the created professor's info
     * @throws EmailNotAllowedException   if the email domain may not register
     * @throws DuplicateResourceException if the email is already registered
     * @throws InvalidOtpException        if the emailed code is missing, wrong or expired
     */
    @Transactional(noRollbackFor = InvalidOtpException.class)
    public RegisterResponse register(RegisterRequest request) {
        String email = EmailOtpService.normalise(request.email());

        // 1. Only allowed email domains (checked again here, not just when the code is sent)
        emailOtpService.assertAllowed(email);

        // 2. Check email uniqueness (before using up the code)
        if (professorRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateResourceException("An account with this email already exists. Log in instead.");
        }

        // 3. Prove the registrant owns the email; the code is deleted on success
        emailOtpService.verifyAndConsume(email, request.otp());

        // 4. Hash the password
        String passwordHash = passwordEncoder.encode(request.password());

        // 5. Build and save professor
        Professor professor = Professor.builder()
                .fullName(request.fullName().trim())
                .email(email)
                .passwordHash(passwordHash)
                .build();

        professor = professorRepository.save(professor);

        log.info("Professor registered: {} ({})", professor.getFullName(), professor.getEmail());

        return new RegisterResponse(
                professor.getId(),
                professor.getEmail(),
                professor.getFullName()
        );
    }

    /**
     * Authenticates a professor and returns a login JWT.
     *
     * @param request the login payload (email, password)
     * @return the JWT token and professor info
     * @throws InvalidCredentialsException if the email is not found or password is wrong
     */
    public LoginResponse login(LoginRequest request) {
        // 1. Find professor by email (exact match first; case-insensitive for accounts
        //    created before emails were normalised to lower case)
        String email = request.email().trim();
        Professor professor = professorRepository.findByEmail(email)
                .or(() -> professorRepository.findByEmailIgnoreCase(email))
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        // 2. Verify password
        if (!passwordEncoder.matches(request.password(), professor.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // 3. Generate login JWT
        String token = jwtUtil.generateLoginToken(professor.getId(), professor.getEmail());

        log.info("Professor logged in: {}", professor.getEmail());

        return new LoginResponse(
                token,
                professor.getId(),
                professor.getEmail(),
                professor.getFullName()
        );
    }
}
