package com.qrattend.dto.auth;

import java.util.UUID;

/**
 * Response body for {@code POST /api/auth/login}.
 */
public record LoginResponse(
        String token,
        UUID professorId,
        String email,
        String fullName
) {}
