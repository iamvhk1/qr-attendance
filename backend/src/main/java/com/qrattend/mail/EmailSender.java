package com.qrattend.mail;

/**
 * Sends a transactional email.
 *
 * <p>Which implementation is active is chosen by {@code app.mail.provider}:
 * {@code brevo} ({@link BrevoEmailSender}, used in production) or {@code log}
 * ({@link LoggingEmailSender}, the local-development default, which prints the
 * email to the console instead of sending it).</p>
 */
public interface EmailSender {

    /**
     * @throws EmailDeliveryException if the email could not be handed to the provider
     */
    void send(String to, String subject, String textBody, String htmlBody);
}
