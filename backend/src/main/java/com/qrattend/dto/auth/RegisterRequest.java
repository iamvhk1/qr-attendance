package com.qrattend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/auth/register}.
 *
 * <p>{@code otp} is the 6-digit code emailed by {@code POST /api/auth/otp}; it proves
 * the registrant owns {@code email}.</p>
 */
public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 100, message = "Email must be at most 100 characters")
        String email,

        @NotBlank(message = "Verification code is required")
        @Pattern(regexp = "\\s*\\d{6}\\s*", message = "Verification code must be 6 digits")
        String otp,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be 8-100 characters")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name must be at most 100 characters")
        String fullName
) {}
