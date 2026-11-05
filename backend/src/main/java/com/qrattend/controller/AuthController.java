package com.qrattend.controller;

import com.qrattend.dto.auth.LoginRequest;
import com.qrattend.dto.auth.LoginResponse;
import com.qrattend.dto.auth.OtpRequest;
import com.qrattend.dto.auth.OtpResponse;
import com.qrattend.dto.auth.RegisterRequest;
import com.qrattend.dto.auth.RegisterResponse;
import com.qrattend.service.AuthService;
import com.qrattend.service.EmailOtpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints — no JWT required.
 *
 * <p>Handles professor self-registration (with an invite code)
 * and professor login (returns a JWT).</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailOtpService emailOtpService;

    /**
     * Emails a 6-digit verification code to the given address. The address must be at an
     * allowed domain (see {@link com.qrattend.service.EmailOtpService}).
     *
     * @return 200 OK with the code's lifetime and the resend cooldown;
     *         400 if the domain isn't allowed; 409 if the email is already registered;
     *         429 (with Retry-After) if a sending limit applies; 503 if the email couldn't be sent
     */
    @PostMapping("/otp")
    public ResponseEntity<OtpResponse> requestOtp(@Valid @RequestBody OtpRequest request) {
        return ResponseEntity.ok(emailOtpService.requestOtp(request.email()));
    }

    /**
     * Registers a new professor account.
     * Requires the verification code emailed by {@code POST /api/auth/otp}.
     *
     * @param request the registration payload
     * @return 201 Created with the new professor's info
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticates a professor and returns a JWT.
     *
     * @param request the login payload (email + password)
     * @return 200 OK with the JWT and professor info
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}
