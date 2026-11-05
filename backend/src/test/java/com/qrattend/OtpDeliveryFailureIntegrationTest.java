package com.qrattend;

import com.qrattend.mail.EmailDeliveryException;
import com.qrattend.repository.EmailOtpRepository;
import com.qrattend.support.RecordingEmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full stack (real transaction, real database): when the email provider fails, the request
 * returns 503 and nothing is saved — so the resend cooldown is not used up and the user can
 * retry immediately.
 *
 * <p>Deliberately not {@code @Transactional}: the point is to observe the service's own
 * transaction rolling back.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OTP — email provider failure")
class OtpDeliveryFailureIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EmailOtpRepository otpRepository;
    @SpyBean   private RecordingEmailSender emailSender;

    private static final String EMAIL = "provider.down@iitm.ac.in";

    @Test
    @DisplayName("Provider down → 503, no code stored, immediate retry succeeds once it recovers")
    void failureRollsBack() throws Exception {
        doThrow(new EmailDeliveryException("simulated outage"))
                .when(emailSender).send(eq(EMAIL), any(), any(), any());

        mockMvc.perform(post("/api/auth/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("try again")));

        assertThat(otpRepository.findByEmail(EMAIL)).as("rolled back").isEmpty();

        // Provider recovers; retrying straight away is not blocked by the 60 s cooldown
        doCallRealMethod().when(emailSender).send(eq(EMAIL), any(), any(), any());

        mockMvc.perform(post("/api/auth/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk());

        assertThat(otpRepository.findByEmail(EMAIL)).isPresent();
        assertThat(emailSender.lastCodeFor(EMAIL)).matches("\\d{6}");

        otpRepository.findByEmail(EMAIL).ifPresent(otpRepository::delete);
    }
}
