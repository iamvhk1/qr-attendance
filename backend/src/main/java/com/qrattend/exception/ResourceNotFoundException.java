package com.qrattend.exception;

/**
 * Thrown when a requested entity is not found.
 * <p>
 * Examples: course not found, student not found, session not found.
 * <p>
 * Mapped to <b>404 Not Found</b> by {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
