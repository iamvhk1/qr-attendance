package com.qrattend.exception;

/**
 * Thrown when authentication fails — wrong email, wrong password, or wrong admin secret.
 * <p>
 * Mapped to <b>401 Unauthorized</b> by {@link GlobalExceptionHandler}.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
