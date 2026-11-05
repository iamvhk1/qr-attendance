package com.qrattend;

import com.qrattend.repository.EmailOtpRepository;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.support.RecordingEmailSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real stack: setting the blocked-domain list to a placeholder (what
 * REGISTRATION_BLOCKED_DOMAINS=none.invalid does on Render) lets smail addresses register.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.registration.blocked-domains=none.invalid")
@DisplayName("Registration with smail temporarily unblocked")
class SmailUnblockedIntegrationTest {

    private static final String EMAIL = "cs24b075@smail.iitm.ac.in";

    @Autowired private MockMvc mockMvc;
    @Autowired private RecordingEmailSender emailSender;

    @Test
    @DisplayName("smail address can request a code and register")
    void smailCanRegister() throws Exception {
        mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk());

        String code = emailSender.lastCodeFor(EMAIL);
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"otp\":\"" + code + "\","
                                + "\"password\":\"Pass1234!\",\"fullName\":\"Student Tester\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Non-IITM addresses are still refused")
    void otherDomainsStillRefused() throws Exception {
        mockMvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@gmail.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @AfterAll
    static void cleanup(@Autowired ProfessorRepository professorRepository,
                        @Autowired EmailOtpRepository otpRepository) {
        professorRepository.findByEmailIgnoreCase(EMAIL).ifPresent(professorRepository::delete);
        otpRepository.findByEmail(EMAIL).ifPresent(otpRepository::delete);
    }
}
