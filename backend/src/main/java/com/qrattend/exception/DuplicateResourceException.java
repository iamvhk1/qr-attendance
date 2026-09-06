package com.qrattend.exception;

/**
 * Thrown when attempting to create a resource that already exists.
 * <p>
 * Examples: registering with an email that's already taken,
 * adding a student with a duplicate roll number in the same course.
 * <p>
 * Mapped to <b>409 Conflict</b> by {@link GlobalExceptionHandler}.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
