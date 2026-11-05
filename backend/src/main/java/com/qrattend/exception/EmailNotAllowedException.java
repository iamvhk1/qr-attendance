package com.qrattend.exception;

/**
 * Thrown when an email address is outside the domains allowed to register
 * (e.g. not an {@code iitm.ac.in} address, or a student {@code smail.iitm.ac.in} address).
 * <p>
 * Mapped to <b>400 Bad Request</b> by {@link GlobalExceptionHandler}.
 */
public class EmailNotAllowedException extends RuntimeException {

    public EmailNotAllowedException(String message) {
        super(message);
    }
}
