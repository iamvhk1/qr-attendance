package com.qrattend.dto.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/admin/invite}.
 */
public record InviteRequest(
        @NotBlank(message = "Admin secret is required")
        String adminSecret
) {}
