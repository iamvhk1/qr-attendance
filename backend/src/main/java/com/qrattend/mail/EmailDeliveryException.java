package com.qrattend.mail;

/** The email provider rejected the message or could not be reached. Mapped to 503. */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
