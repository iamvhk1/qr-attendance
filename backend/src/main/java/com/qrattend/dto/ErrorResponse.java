package com.qrattend.dto;

import java.time.Instant;

/**
 * Standard error response body returned by the {@code GlobalExceptionHandler}.
 * Provides a consistent JSON shape for all error responses.
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp
) {
    /**
     * Convenience factory method — fills in the timestamp automatically.
     */
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, Instant.now());
    }
}
