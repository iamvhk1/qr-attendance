package com.qrattend.dto.session;

import com.qrattend.entity.Doubt;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO returned by {@code GET /api/sessions/{id}/doubts}.
 * Maps the {@code question} entity field to {@code text} so the frontend
 * can consume it with its {@code Doubt} interface ({@code id, sessionId, text, postedAt}).
 */
public record DoubtResponse(
        UUID id,
        UUID sessionId,
        String text,
        Instant postedAt
) {
    public static DoubtResponse from(Doubt d) {
        return new DoubtResponse(
                d.getId(),
                d.getSession().getId(),
                d.getQuestion(),
                d.getPostedAt()
        );
    }
}
