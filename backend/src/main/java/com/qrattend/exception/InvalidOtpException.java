package com.qrattend.exception;

/**
 * Thrown when a registration's email verification code is wrong, expired, missing,
 * or has had too many wrong guesses.
 * <p>
 * Mapped to <b>400 Bad Request</b> by {@link GlobalExceptionHandler}.
 */
public class InvalidOtpException extends RuntimeException {

    public InvalidOtpException(String message) {
        super(message);
    }
}
