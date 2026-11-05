package com.qrattend.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local-development sender: prints the email to the console instead of sending it,
 * so registration can be tested without an email provider.
 *
 * <p>Active when {@code app.mail.provider=log} (the default). Production sets
 * {@code app.mail.provider=brevo}, so this never runs there.</p>
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "log", matchIfMissing = true)
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String textBody, String htmlBody) {
        log.warn("""

                ===== EMAIL NOT SENT (app.mail.provider=log) =====
                To:      {}
                Subject: {}

                {}
                ==================================================""", to, subject, textBody);
    }
}
