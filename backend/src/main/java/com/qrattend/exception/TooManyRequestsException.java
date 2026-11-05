package com.qrattend.exception;

import lombok.Getter;

/**
 * Thrown when a client must wait before retrying (e.g. resending a verification code too soon).
 * <p>
 * Mapped to <b>429 Too Many Requests</b> with a {@code Retry-After} header by {@link GlobalExceptionHandler}.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}
