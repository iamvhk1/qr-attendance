package com.qrattend.exception;

/**
 * Thrown when a professor tries to register with an invalid, expired, or tampered invite code.
 * <p>
 * Mapped to <b>400 Bad Request</b> by {@link GlobalExceptionHandler}.
 */
public class InvalidInviteCodeException extends RuntimeException {

    public InvalidInviteCodeException(String message) {
        super(message);
    }
}
