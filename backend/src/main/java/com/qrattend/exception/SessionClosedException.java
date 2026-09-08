package com.qrattend.exception;

/**
 * Thrown when an operation is attempted on a QR session that has already
 * been closed (either explicitly by the professor or by timer expiry).
 * <p>
 * Mapped to <b>409 Conflict</b> by {@link GlobalExceptionHandler}.
 */
public class SessionClosedException extends RuntimeException {

    public SessionClosedException(String message) {
        super(message);
    }
}
