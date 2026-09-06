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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles professor registration and login.
 *
 * <p><b>Register flow:</b> validate invite code → check email uniqueness →
 * bcrypt password → save professor.</p>
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

    /**
     * Registers a new professor account.
     *
     * @param request the registration payload (invite code, email, password, name)
     * @return the created professor's info
     * @throws InvalidInviteCodeException if the invite code is invalid or expired
     * @throws DuplicateResourceException if the email is already registered
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        // 1. Validate invite code (must be a valid, non-expired INVITE JWT)
        if (!jwtUtil.isInviteToken(request.inviteCode())) {
            throw new InvalidInviteCodeException("Invalid or expired invite code");
        }

        // 2. Check email uniqueness
        if (professorRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email is already registered: " + request.email());
        }

        // 3. Hash the password
        String passwordHash = passwordEncoder.encode(request.password());

        // 4. Build and save professor
        Professor professor = Professor.builder()
                .fullName(request.fullName())
                .email(request.email())
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
        // 1. Find professor by email
        Professor professor = professorRepository.findByEmail(request.email())
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
