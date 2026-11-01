package com.qrattend.exception;

/**
 * Thrown when a professor attempts to start a new QR attendance session
 * while already running the maximum allowed number of concurrent live sessions.
 */
public class MaxConcurrentSessionsException extends RuntimeException {

    public MaxConcurrentSessionsException(String message) {
        super(message);
    }
}
