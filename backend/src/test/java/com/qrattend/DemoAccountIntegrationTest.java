package com.qrattend;

import com.qrattend.repository.ProfessorRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real stack: with DEMO_ACCOUNT_EMAIL / DEMO_ACCOUNT_PASSWORD set, the demo account exists at
 * startup and can log in and use the app, even though its email isn't an IITM address.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.demo.email=demo@qr-attendance.demo",
        "app.demo.password=Integration-Demo-Pass-1",
        "app.demo.full-name=Demo Professor",
})
@DisplayName("Demo account — end to end")
class DemoAccountIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Demo account logs in and can use professor endpoints")
    void demoAccountLogsIn() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo@qr-attendance.demo\",\"password\":\"Integration-Demo-Pass-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Demo Professor"))
                .andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("token").asText();

        mockMvc.perform(get("/api/courses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Wrong password for the demo account → 401")
    void wrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo@qr-attendance.demo\",\"password\":\"nope-nope-nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("The demo domain still can't be used to self-register")
    void demoDomainCannotRegister() throws Exception {
        mockMvc.perform(post("/api/auth/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"another@qr-attendance.demo\"}"))
                .andExpect(status().isBadRequest());
    }

    @AfterAll
    static void cleanup(@Autowired ProfessorRepository professorRepository) {
        professorRepository.findByEmailIgnoreCase("demo@qr-attendance.demo").ifPresent(professorRepository::delete);
    }
}
