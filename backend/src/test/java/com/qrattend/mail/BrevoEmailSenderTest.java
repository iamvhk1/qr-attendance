package com.qrattend.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Tests {@link BrevoEmailSender} against a mock HTTP server: the exact request sent to
 * Brevo's {@code POST /v3/smtp/email}, and how failures are reported.
 */
@DisplayName("BrevoEmailSender")
class BrevoEmailSenderTest {

    private MockRestServiceServer server;
    private BrevoEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new BrevoEmailSender(builder, "test-api-key", "https://api.brevo.test",
                "noreply@iitm.ac.in", "QR Attendance");
    }

    @Test
    @DisplayName("Posts the message to /v3/smtp/email with the API key and the expected JSON")
    void sendsExpectedRequest() {
        server.expect(requestTo("https://api.brevo.test/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-api-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("noreply@iitm.ac.in"))
                .andExpect(jsonPath("$.sender.name").value("QR Attendance"))
                .andExpect(jsonPath("$.to[0].email").value("prof@iitm.ac.in"))
                .andExpect(jsonPath("$.subject").value("Your code"))
                .andExpect(jsonPath("$.textContent").value("Code: 123456"))
                .andExpect(jsonPath("$.htmlContent").value("<p>123456</p>"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"<abc@brevo>\"}"));

        sender.send("prof@iitm.ac.in", "Your code", "Code: 123456", "<p>123456</p>");

        server.verify();
    }

    @Test
    @DisplayName("Brevo rejecting the request (e.g. bad API key) → EmailDeliveryException")
    void clientErrorBecomesDeliveryException() {
        server.expect(requestTo("https://api.brevo.test/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        assertThatThrownBy(() -> sender.send("prof@iitm.ac.in", "s", "t", "h"))
                .isInstanceOf(EmailDeliveryException.class);
    }

    @Test
    @DisplayName("Brevo server error → EmailDeliveryException")
    void serverErrorBecomesDeliveryException() {
        server.expect(requestTo("https://api.brevo.test/v3/smtp/email")).andRespond(withServerError());

        assertThatThrownBy(() -> sender.send("prof@iitm.ac.in", "s", "t", "h"))
                .isInstanceOf(EmailDeliveryException.class);
    }

    @Test
    @DisplayName("A missing API key or sender address fails at startup, not at first send")
    void missingConfigFailsFast() {
        assertThatThrownBy(() -> new BrevoEmailSender(RestClient.builder(), " ", "https://x", "a@b.c", "n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BREVO_API_KEY");
        assertThatThrownBy(() -> new BrevoEmailSender(RestClient.builder(), "key", "https://x", "", "n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_FROM");
    }
}
