package com.qrattend.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Sends email through Brevo's transactional email HTTP API
 * ({@code POST /v3/smtp/email}). Uses HTTPS rather than SMTP, so it works on hosts
 * that block outgoing SMTP ports.
 *
 * <p>Active when {@code app.mail.provider=brevo}. Requires
 * {@code app.mail.brevo.api-key} and {@code app.mail.from-address}; the sender
 * address must be verified in the Brevo account.</p>
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "brevo")
@Slf4j
public class BrevoEmailSender implements EmailSender {

    private final RestClient restClient;
    private final String fromAddress;
    private final String fromName;

    public BrevoEmailSender(
            RestClient.Builder builder,
            @Value("${app.mail.brevo.api-key}") String apiKey,
            @Value("${app.mail.brevo.base-url:https://api.brevo.com}") String baseUrl,
            @Value("${app.mail.from-address}") String fromAddress,
            @Value("${app.mail.from-name:QR Attendance}") String fromName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("app.mail.brevo.api-key (BREVO_API_KEY) must be set when app.mail.provider=brevo");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException("app.mail.from-address (MAIL_FROM) must be set when app.mail.provider=brevo");
        }
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("api-key", apiKey)
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    @Override
    public void send(String to, String subject, String textBody, String htmlBody) {
        Map<String, Object> body = Map.of(
                "sender", Map.of("email", fromAddress, "name", fromName),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "textContent", textBody,
                "htmlContent", htmlBody);
        try {
            restClient.post()
                    .uri("/v3/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Email sent via Brevo to {} ({})", to, subject);
        } catch (RestClientException e) {
            // Never log the API key; the exception message from RestClient doesn't include headers.
            log.error("Brevo rejected or could not deliver email to {}: {}", to, e.getMessage());
            throw new EmailDeliveryException("Could not send email", e);
        }
    }
}
