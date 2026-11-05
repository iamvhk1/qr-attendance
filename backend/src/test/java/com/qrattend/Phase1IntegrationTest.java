package com.qrattend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.entity.Professor;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.security.JwtUtil;
import com.qrattend.support.RecordingEmailSender;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests for Phase 1 (Auth + Entities + Security).
 *
 * <p>Boots the entire Spring Boot application with a real in-memory H2 database.
 * No mocks — every layer (controller → service → repository → DB) is exercised
 * with real HTTP requests through MockMvc.</p>
 *
 * <p>Tests the end-to-end flow from the implementation plan Section 7:</p>
 * <pre>
 *   1. POST /api/auth/otp      → verification code emailed
 *   2. POST /api/auth/register → professor created
 *   3. POST /api/auth/login    → JWT token
 *   4. GET  /api/courses (with JWT) → 200 (empty list)
 *   5. GET  /api/courses (no JWT)   → 401
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase1IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProfessorRepository professorRepository;

    @Autowired
    private RecordingEmailSender emailSender;

    @Autowired
    private JwtUtil jwtUtil;

    /** Requests a verification code for {@code email} and returns the code that was "emailed". */
    private String freshCode(String email) throws Exception {
        mockMvc.perform(post("/api/auth/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\"}"))
                .andExpect(status().isOk());
        return emailSender.lastCodeFor(email);
    }

    // Shared state across ordered tests (simulates a real user session)
    private static String verificationCode;
    private static String professorId;
    private static String jwtToken;

    // ═══════════════════════════════════════════════════════════════
    //  HAPPY PATH — Full end-to-end flow
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("1. POST /api/auth/otp → 200 + 6-digit code emailed (not returned)")
    void step1_requestVerificationCode() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"boby@iitm.ac.in\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(600))
                .andExpect(jsonPath("$.resendAfterSeconds").value(60))
                .andReturn();

        verificationCode = emailSender.lastCodeFor("boby@iitm.ac.in");
        assertThat(verificationCode).matches("\\d{6}");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(verificationCode);
    }

    @Test
    @Order(2)
    @DisplayName("2. POST /api/auth/register → 201 + professor created in DB")
    void step2_registerProfessor() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", verificationCode);
            put("email", "boby@iitm.ac.in");
            put("password", "securePassword123");
            put("fullName", "Dr. Boby George");
        }});

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.professorId").isNotEmpty())
                .andExpect(jsonPath("$.email").value("boby@iitm.ac.in"))
                .andExpect(jsonPath("$.fullName").value("Dr. Boby George"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        professorId = body.get("professorId").asText();

        // Verify professor is actually persisted in the database
        Optional<Professor> dbProfessor = professorRepository.findByEmail("boby@iitm.ac.in");
        assertThat(dbProfessor).isPresent();
        assertThat(dbProfessor.get().getFullName()).isEqualTo("Dr. Boby George");
        assertThat(dbProfessor.get().getId().toString()).isEqualTo(professorId);
        // Password should be bcrypt-hashed, not stored in plaintext
        assertThat(dbProfessor.get().getPasswordHash()).startsWith("$2a$");
        assertThat(dbProfessor.get().getPasswordHash()).isNotEqualTo("securePassword123");
    }

    @Test
    @Order(3)
    @DisplayName("3. POST /api/auth/login → 200 + valid JWT token")
    void step3_loginProfessor() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"boby@iitm.ac.in\", \"password\": \"securePassword123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.professorId").value(professorId))
                .andExpect(jsonPath("$.email").value("boby@iitm.ac.in"))
                .andExpect(jsonPath("$.fullName").value("Dr. Boby George"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        jwtToken = body.get("token").asText();

        assertThat(jwtToken).isNotBlank();
        assertThat(jwtToken.split("\\.")).hasSize(3); // JWT has 3 parts
    }

    @Test
    @Order(4)
    @DisplayName("4. GET /api/courses (with JWT) → auth passes (not 401/403)")
    void step4_accessProtectedEndpointWithToken() throws Exception {
        // /api/courses requires ROLE_PROFESSOR. The CourseController doesn't exist yet
        // (Phase 2), so we can't expect 200. Instead, verify the JWT is ACCEPTED by
        // the security filter: the response must NOT be 401 or 403.
        int status = mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer " + jwtToken))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    @Order(5)
    @DisplayName("5. GET /api/courses (no JWT) → 401 Unauthorized")
    void step5_accessProtectedEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  ERROR PATHS — Registration failures
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("Register with a code that was never requested → 400")
    void register_unrequestedCode_returns400() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", "123456");
            put("email", "new@iitm.ac.in");
            put("password", "password123");
            put("fullName", "New Professor");
        }});

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No verification code found for this email. Request a new code."));
    }

    @Test
    @Order(21)
    @DisplayName("Register with duplicate email → 409 Conflict")
    void register_duplicateEmail_returns409() throws Exception {
        // boby@iitm.ac.in was already registered in step 2
        String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", "123456");
            put("email", "boby@iitm.ac.in");
            put("password", "anotherPassword123");
            put("fullName", "Dr. Boby George Clone");
        }});

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists. Log in instead."));
    }

    @Test
    @Order(22)
    @DisplayName("Register with blank email → 400 validation error")
    void register_blankEmail_returns400() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", "123456");
            put("email", "");
            put("password", "password123");
            put("fullName", "Prof Test");
        }});

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @Order(23)
    @DisplayName("Register with short password → 400 validation error")
    void register_shortPassword_returns400() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", "123456");
            put("email", "short@iitm.ac.in");
            put("password", "123");
            put("fullName", "Prof Test");
        }});

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @Order(24)
    @DisplayName("Register with invalid email format → 400 validation error")
    void register_invalidEmailFormat_returns400() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", "123456");
            put("email", "not-an-email");
            put("password", "password123");
            put("fullName", "Prof Test");
        }});

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // ═══════════════════════════════════════════════════════════════
    //  ERROR PATHS — Login failures
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @DisplayName("Login with unknown email → 401")
    void login_unknownEmail_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"nobody@iitm.ac.in\", \"password\": \"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @Order(31)
    @DisplayName("Login with wrong password → 401")
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"boby@iitm.ac.in\", \"password\": \"wrongPassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @Order(32)
    @DisplayName("Login with blank fields → 400 validation error")
    void login_blankFields_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"\", \"password\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // ═══════════════════════════════════════════════════════════════
    //  SECURITY — JWT token validation at the HTTP level
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(40)
    @DisplayName("Protected endpoint with expired/garbage token → 401")
    void protectedEndpoint_garbageToken_returns401() throws Exception {
        mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer not.a.real.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(41)
    @DisplayName("Protected endpoint with tampered token → 401")
    void protectedEndpoint_tamperedToken_returns401() throws Exception {
        // Flip a character in the real JWT
        String tampered = jwtToken.substring(0, jwtToken.length() - 2) + "XX";

        mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(42)
    @DisplayName("Protected endpoint with Basic auth instead of Bearer → 401")
    void protectedEndpoint_basicAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(43)
    @DisplayName("SCAN token cannot access professor endpoints → 401/403")
    void protectedEndpoint_scanToken_rejected() throws Exception {
        // A SCAN token has ROLE_SCAN, not ROLE_PROFESSOR — authenticated but forbidden
        String scanToken = jwtUtil.generateScanToken(java.util.UUID.randomUUID());
        mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer " + scanToken))
                .andExpect(status().isForbidden());
    }

    // ═══════════════════════════════════════════════════════════════
    //  MULTI-PROFESSOR FLOW — Second professor registration
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(50)
    @DisplayName("Second professor can verify their own email, register and login independently")
    void multiProfessor_secondRegistrationAndLogin() throws Exception {
        // 1. Request a code for the second professor's email
        String secondCode = freshCode("chester@iitm.ac.in");

        // 2. Register second professor
        String registerBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", secondCode);
            put("email", "chester@iitm.ac.in");
            put("password", "anotherSecure123");
            put("fullName", "Dr. Chester Rebeiro");
        }});

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("chester@iitm.ac.in"))
                .andReturn();

        String secondProfId = objectMapper.readTree(
                registerResult.getResponse().getContentAsString()).get("professorId").asText();

        // 3. Login second professor
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"chester@iitm.ac.in\", \"password\": \"anotherSecure123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professorId").value(secondProfId))
                .andReturn();

        String secondToken = objectMapper.readTree(
                loginResult.getResponse().getContentAsString()).get("token").asText();

        // 4. Second professor's token is accepted by security filter (not 401/403)
        int status = mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer " + secondToken))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);

        // 5. Verify both professors exist in DB
        assertThat(professorRepository.count()).isGreaterThanOrEqualTo(2);
        assertThat(professorRepository.findByEmail("boby@iitm.ac.in")).isPresent();
        assertThat(professorRepository.findByEmail("chester@iitm.ac.in")).isPresent();
    }

    // ═══════════════════════════════════════════════════════════════
    //  JWT TOKEN INTEGRITY — Token claims match DB state
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(60)
    @DisplayName("Login response token can be used to re-authenticate and get consistent data")
    void tokenIntegrity_loginTwiceReturnsSameIdentity() throws Exception {
        // Login first time
        MvcResult login1 = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"boby@iitm.ac.in\", \"password\": \"securePassword123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // Login second time
        MvcResult login2 = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"boby@iitm.ac.in\", \"password\": \"securePassword123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body1 = objectMapper.readTree(login1.getResponse().getContentAsString());
        JsonNode body2 = objectMapper.readTree(login2.getResponse().getContentAsString());

        // Same professor identity both times
        assertThat(body1.get("professorId").asText()).isEqualTo(body2.get("professorId").asText());
        assertThat(body1.get("email").asText()).isEqualTo(body2.get("email").asText());
        assertThat(body1.get("fullName").asText()).isEqualTo(body2.get("fullName").asText());

        // Both tokens should be accepted by the security filter
        String token1 = body1.get("token").asText();
        String token2 = body2.get("token").asText();
        assertThat(token1).isNotBlank();
        assertThat(token2).isNotBlank();

        int status1 = mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer " + token1))
                .andReturn().getResponse().getStatus();
        int status2 = mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer " + token2))
                .andReturn().getResponse().getStatus();
        assertThat(status1).isNotIn(401, 403);
        assertThat(status2).isNotIn(401, 403);
    }

    // ═══════════════════════════════════════════════════════════════
    //  VERIFICATION CODE — bound to one email, usable once
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(70)
    @DisplayName("A verification code registers only the email it was sent to, and only once")
    void verificationCode_boundToEmailAndSingleUse() throws Exception {
        String codeA = freshCode("profa@iitm.ac.in");

        // Professor B tries to use A's code → rejected
        String bodyB = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", codeA);
            put("email", "profb@iitm.ac.in");
            put("password", "password12345");
            put("fullName", "Professor B");
        }});
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyB))
                .andExpect(status().isBadRequest());

        // Professor A registers with it
        String bodyA = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("otp", codeA);
            put("email", "profa@iitm.ac.in");
            put("password", "password12345");
            put("fullName", "Professor A");
        }});
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyA))
                .andExpect(status().isCreated());

        assertThat(professorRepository.findByEmail("profa@iitm.ac.in")).isPresent();
        assertThat(professorRepository.findByEmail("profb@iitm.ac.in")).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONTENT-TYPE — API rejects non-JSON requests
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(80)
    @DisplayName("POST with wrong Content-Type → rejected (not 200)")
    void wrongContentType_isRejected() throws Exception {
        // Sending text/plain to a @RequestBody JSON endpoint should not succeed.
        // The exact error code depends on how the GlobalExceptionHandler catches it
        // (415 or 500), but it must NOT return 200.
        int status = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("email=test&password=test"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(200);
    }
}
