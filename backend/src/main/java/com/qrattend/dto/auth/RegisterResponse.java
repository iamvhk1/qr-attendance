package com.qrattend.dto.auth;

import java.util.UUID;

/**
 * Response body for {@code POST /api/auth/register}.
 */
public record RegisterResponse(
        UUID professorId,
        String email,
        String fullName
) {}
