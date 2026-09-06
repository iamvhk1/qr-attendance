package com.qrattend.dto.admin;

/**
 * Response body for {@code POST /api/admin/invite}.
 */
public record InviteResponse(
        String inviteCode
) {}
