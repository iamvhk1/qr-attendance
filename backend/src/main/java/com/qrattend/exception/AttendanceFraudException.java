package com.qrattend.exception;

/**
 * Thrown when a heartbeat request reveals evidence of automated presence simulation
 * (e.g. {@code navigator.webdriver = true}).
 *
 * <p>Mapped to <b>409 Conflict</b> by {@link GlobalExceptionHandler} — the same
 * status as {@link SessionClosedException} — to signal to the client that the
 * request was understood but the attendance has been definitively invalidated.</p>
 *
 * <p>Distinct from {@link SessionClosedException} so that audit logs and future
 * reporting modules can unambiguously identify fraud-invalidated records vs.
 * legitimately closed sessions.</p>
 */
public class AttendanceFraudException extends RuntimeException {

    public AttendanceFraudException(String message) {
        super(message);
    }
}
