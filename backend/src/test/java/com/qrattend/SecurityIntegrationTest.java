package com.qrattend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.entity.*;
import com.qrattend.repository.*;
import com.qrattend.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack security integration tests for all phases.
 *
 * <p>Each test is fully self-contained — it sets up its own data via the
 * {@code @BeforeEach} helper and tears it down via {@code @Transactional} rollback.
 * No shared static state between tests.</p>
 *
 * <p>Covers every security attack vector:</p>
 * <ul>
 *   <li>Missing / malformed / tampered JWT</li>
 *   <li>Token type misuse (SCAN as LOGIN, LOGIN as SCAN, retired INVITE tokens)</li>
 *   <li>Algorithm confusion (alg:none)</li>
 *   <li>Cross-professor resource isolation</li>
 *   <li>Horizontal privilege escalation (accessing another professor's sessions/courses)</li>
 *   <li>Registration requires a verified iitm.ac.in email (OTP)</li>
 *   <li>ROLE boundary enforcement (PROFESSOR vs SCAN endpoints)</li>
 *   <li>Password not leaked in any response</li>
 *   <li>Scan token short-expiry enforcement</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Security Integration Tests")
@Transactional      // Each test rolls back → perfectly clean DB for every test
class SecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ProfessorRepository professorRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private QrSessionRepository sessionRepository;
    @Autowired private DoubtRepository doubtRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.qrattend.support.RecordingEmailSender emailSender;
    @org.springframework.beans.factory.annotation.Value("${app.jwt.secret}") private String jwtSecret;

    // ── Helpers ─────────────────────────────────────────────────────

    /** Creates a professor directly in the DB and returns their login JWT. */
    private String createProfessorAndLogin(String email, String password) {
        Professor prof = professorRepository.save(
                Professor.builder()
                        .email(email)
                        .fullName("Test Prof " + email)
                        .passwordHash(passwordEncoder.encode(password))
                        .build());
        return jwtUtil.generateLoginToken(prof.getId(), prof.getEmail());
    }

    /** Creates a course for the given professor and returns its UUID. */
    private UUID createCourse(String jwt) throws Exception {
        UUID profId = jwtUtil.extractProfessorId(jwt);
        Professor prof = professorRepository.findById(profId).orElseThrow();

        Course course = courseRepository.save(
                Course.builder()
                        .professor(prof)
                        .name("Test Course")
                        .code("TEST101")
                        .semester("2026")
                        .build());
        return course.getId();
    }

    /** Creates a live QR session for the given course+professor. */
    private UUID createLiveSession(UUID courseId, UUID profId) {
        Professor prof = professorRepository.findById(profId).orElseThrow();
        Course course = courseRepository.findById(courseId).orElseThrow();
        return sessionRepository.save(
                QrSession.builder()
                        .course(course)
                        .professor(prof)
                        .expiresAt(Instant.now().plus(120, ChronoUnit.SECONDS))
                        .build()).getId();
    }

    /** Creates an expired QR session for testing closed-session rejection. */
    private UUID createExpiredSession(UUID courseId, UUID profId) {
        Professor prof = professorRepository.findById(profId).orElseThrow();
        Course course = courseRepository.findById(courseId).orElseThrow();
        return sessionRepository.save(
                QrSession.builder()
                        .course(course)
                        .professor(prof)
                        .expiresAt(Instant.now().minus(5, ChronoUnit.MINUTES))
                        .build()).getId();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 1: Missing / Absent Authorization Header
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 1 — Missing Authorization")
    class MissingAuthorization {

        @Test
        @DisplayName("No Authorization header → 401 on GET /api/courses")
        void noHeader_courses_returns401() throws Exception {
            mockMvc.perform(get("/api/courses"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("No Authorization header → 401 on POST /api/courses")
        void noHeader_createCourse_returns401() throws Exception {
            mockMvc.perform(post("/api/courses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"X\",\"code\":\"Y\",\"semester\":\"Z\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("No Authorization header → 401 on POST /api/sessions")
        void noHeader_createSession_returns401() throws Exception {
            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"courseId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("No Authorization header → 401 on GET /api/sessions/{id}/qr")
        void noHeader_qrEndpoint_returns401() throws Exception {
            mockMvc.perform(get("/api/sessions/" + UUID.randomUUID() + "/qr"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Authorization header present but no Bearer prefix → 401")
        void noBearerPrefix_returns401() throws Exception {
            String jwt = createProfessorAndLogin("nobearer@test.com", "Pass1234!");
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", jwt))   // missing "Bearer "
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Empty Authorization header value → 401")
        void emptyAuthHeader_returns401() throws Exception {
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", ""))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 2: Malformed / Tampered JWT
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 2 — Malformed / Tampered JWT")
    class MalformedJwt {

        @Test
        @DisplayName("Garbage string as token → 401")
        void garbageToken_returns401() throws Exception {
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer not.a.jwt"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Completely random string as token → 401")
        void randomString_returns401() throws Exception {
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Valid JWT with signature tampered (last 4 chars replaced) → 401")
        void tamperedSignature_returns401() throws Exception {
            String jwt = createProfessorAndLogin("tamper@test.com", "Pass1234!");
            String tampered = jwt.substring(0, jwt.length() - 4) + "XXXX";
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + tampered))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Valid JWT with payload tampered (middle section modified) → 401")
        void tamperedPayload_returns401() throws Exception {
            String jwt = createProfessorAndLogin("payload@test.com", "Pass1234!");
            String[] parts = jwt.split("\\.");
            // Flip a char in the payload
            String badPayload = parts[1].substring(0, 5) + "AAAAA" + parts[1].substring(10);
            String tampered = parts[0] + "." + badPayload + "." + parts[2];
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + tampered))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("JWT signed with a different secret key → 401")
        void differentSecretJwt_returns401() throws Exception {
            // Build a JWT signed with a totally different HMAC key inline (no JwtUtil needed)
            javax.crypto.SecretKey otherKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                    "another-secret-key-that-is-different-from-original-key-32b"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String foreignJwt = io.jsonwebtoken.Jwts.builder()
                    .subject(UUID.randomUUID().toString())
                    .claim("type", "LOGIN")
                    .claim("email", "evil@evil.com")
                    .issuedAt(new java.util.Date())
                    .expiration(new java.util.Date(System.currentTimeMillis() + 86400000))
                    .signWith(otherKey)
                    .compact();

            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + foreignJwt))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("alg:none attack — unsigned JWT → 401")
        void algNoneAttack_returns401() throws Exception {
            // Hand-crafted unsigned JWT: header.payload. (empty signature)
            // JJWT must reject this
            String unsignedJwt = "eyJhbGciOiJub25lIn0" +
                    ".eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJ0eXBlIjoiTE9HSU4iLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6OTk5OTk5OTk5OX0" +
                    ".";
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + unsignedJwt))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("JWT with only two parts (missing signature) → 401")
        void twoPartJwt_returns401() throws Exception {
            String jwt = createProfessorAndLogin("twopart@test.com", "Pass1234!");
            String[] parts = jwt.split("\\.");
            String twoPartJwt = parts[0] + "." + parts[1]; // no signature
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + twoPartJwt))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Expired LOGIN token → 401")
        void expiredToken_returns401() throws Exception {
            // Generate a token that expired 1 second ago
            JwtUtil expiredUtil = new JwtUtil(
                    "c3VwZXItc2VjcmV0LWtleS1mb3ItcXItYXR0ZW5kYW5jZS1zeXN0ZW0tMjAyNi1jaGFuZ2UtaW4tcHJvZA==",
                    -1000L, 15000L, 20000L, 7200000L);
            String expiredJwt = expiredUtil.generateLoginToken(UUID.randomUUID(), "expired@test.com");

            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + expiredJwt))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 3: Token Type Misuse
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 3 — Token Type Misuse")
    class TokenTypeMisuse {

        @Test
        @DisplayName("Retired INVITE-type token (validly signed) on professor endpoint → 401")
        void inviteTokenOnProfessorEndpoint_returns401() throws Exception {
            // Invite codes were replaced by email OTP; a correctly signed token of the old
            // INVITE type must not authenticate anything.
            String inviteJwt = io.jsonwebtoken.Jwts.builder()
                    .subject("admin-invite")
                    .claim("type", "INVITE")
                    .issuedAt(new java.util.Date())
                    .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000))
                    .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                            jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .compact();
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + inviteJwt))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("SCAN token used on professor endpoint → 403 (has ROLE_SCAN, needs ROLE_PROFESSOR)")
        void scanTokenOnProfessorEndpoint_returns403() throws Exception {
            // SCAN tokens ARE accepted by the filter (they're valid JWTs) but get ROLE_SCAN
            // which is insufficient for /api/courses/** (needs ROLE_PROFESSOR)
            String scanJwt = jwtUtil.generateScanToken(UUID.randomUUID());
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + scanJwt))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SCAN token cannot create a course → 403")
        void scanTokenCannotCreateCourse_returns403() throws Exception {
            String scanJwt = jwtUtil.generateScanToken(UUID.randomUUID());
            mockMvc.perform(post("/api/courses")
                            .header("Authorization", "Bearer " + scanJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"X\",\"code\":\"Y\",\"semester\":\"Z\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SCAN token cannot create a session → 403")
        void scanTokenCannotCreateSession_returns403() throws Exception {
            String scanJwt = jwtUtil.generateScanToken(UUID.randomUUID());
            mockMvc.perform(post("/api/sessions")
                            .header("Authorization", "Bearer " + scanJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"courseId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SCAN token cannot get QR image → 403 (needs ROLE_PROFESSOR on /api/sessions/**)")
        void scanTokenCannotGetQrImage_returns403() throws Exception {
            String scanJwt = jwtUtil.generateScanToken(UUID.randomUUID());
            mockMvc.perform(get("/api/sessions/" + UUID.randomUUID() + "/qr")
                            .header("Authorization", "Bearer " + scanJwt))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("LOGIN token cannot access SCAN-only student endpoints → 403")
        void loginTokenOnScanEndpoint_returns403() throws Exception {
            String jwt = createProfessorAndLogin("scantest@test.com", "Pass1234!");
            // /api/student/scan requires ROLE_SCAN — LOGIN token has ROLE_PROFESSOR
            mockMvc.perform(post("/api/student/scan")
                            .header("Authorization", "Bearer " + jwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rollNumber\":\"CS001\"}"))
                    .andExpect(status().isForbidden());
        }

    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 4: Cross-Professor Resource Isolation (IDOR)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 4 — Cross-Professor Isolation (IDOR)")
    class CrossProfessorIsolation {

        @Test
        @DisplayName("Professor B cannot GET Professor A's course → 403")
        void profBCannotGetProfACourse() throws Exception {
            String jwtA = createProfessorAndLogin("isorA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("isorB@test.com", "Pass1234!");
            UUID courseIdA = createCourse(jwtA);

            mockMvc.perform(get("/api/courses/" + courseIdA)
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Professor B cannot DELETE Professor A's course → 403")
        void profBCannotDeleteProfACourse() throws Exception {
            String jwtA = createProfessorAndLogin("delA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("delB@test.com", "Pass1234!");
            UUID courseIdA = createCourse(jwtA);

            mockMvc.perform(delete("/api/courses/" + courseIdA)
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Professor B's GET /api/courses does NOT return Professor A's courses")
        void listCourses_isolatesPerProfessor() throws Exception {
            String jwtA = createProfessorAndLogin("listA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("listB@test.com", "Pass1234!");
            createCourse(jwtA);   // Prof A creates a course

            // Prof B's list should be empty
            mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Professor B cannot list students in Professor A's course → 403")
        void profBCannotListStudentsInProfACourse() throws Exception {
            String jwtA = createProfessorAndLogin("stuA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("stuB@test.com", "Pass1234!");
            UUID courseIdA = createCourse(jwtA);

            mockMvc.perform(get("/api/courses/" + courseIdA + "/students")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Professor B cannot add a student to Professor A's course → 403")
        void profBCannotAddStudentToProfACourse() throws Exception {
            String jwtA = createProfessorAndLogin("addStuA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("addStuB@test.com", "Pass1234!");
            UUID courseIdA = createCourse(jwtA);

            mockMvc.perform(post("/api/courses/" + courseIdA + "/students")
                            .header("Authorization", "Bearer " + jwtB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rollNumber\":\"EV24I001\",\"fullName\":\"Evil Student\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Professor B cannot import students into Professor A's course → 403")
        void profBCannotImportStudentsIntoProfACourse() throws Exception {
            String jwtA = createProfessorAndLogin("impA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("impB@test.com", "Pass1234!");
            UUID courseIdA = createCourse(jwtA);

            // Minimal valid Excel bytes (just testing authorization, not parsing)
            MockMultipartFile file = new MockMultipartFile(
                    "file", "students.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    new byte[]{});

            mockMvc.perform(multipart("/api/courses/" + courseIdA + "/students/import")
                            .file(file)
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Professor B cannot GET Professor A's session details → 403")
        void profBCannotGetProfASession() throws Exception {
            String jwtA = createProfessorAndLogin("sesA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("sesB@test.com", "Pass1234!");
            UUID courseIdA = createCourse(jwtA);
            UUID profAId = jwtUtil.extractProfessorId(jwtA);
            UUID sessionIdA = createLiveSession(courseIdA, profAId);

            mockMvc.perform(get("/api/sessions/" + sessionIdA)
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Professor B cannot get QR image for Professor A's session → 403")
        void profBCannotGetQrForProfASession() throws Exception {
            String jwtA = createProfessorAndLogin("qrA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("qrB@test.com", "Pass1234!");
            UUID courseIdA = createCourse(jwtA);
            UUID profAId = jwtUtil.extractProfessorId(jwtA);
            UUID sessionIdA = createLiveSession(courseIdA, profAId);

            mockMvc.perform(get("/api/sessions/" + sessionIdA + "/qr")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 5: Registration requires a verified iitm.ac.in email
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 5 — Registration requires a verified email")
    class EmailVerifiedRegistration {

        private org.springframework.test.web.servlet.ResultActions requestOtp(String email) throws Exception {
            return mockMvc.perform(post("/api/auth/otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\"}"));
        }

        private org.springframework.test.web.servlet.ResultActions register(String email, String otp) throws Exception {
            return mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"otp\":\"" + otp + "\"," +
                            "\"password\":\"Pass1234!\",\"fullName\":\"Verified Prof\"}"));
        }

        @Test
        @DisplayName("Full flow: request code → register with it → log in")
        void fullFlow() throws Exception {
            String email = "flow.prof@cse.iitm.ac.in";
            requestOtp(email).andExpect(status().isOk())
                    .andExpect(jsonPath("$.expiresInSeconds").value(600));
            String code = emailSender.lastCodeFor(email);

            register(email, code).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value(email));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"Pass1234!\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isNotEmpty());
        }

        @Test
        @DisplayName("Mixed-case email: code works, account stored lower-case, login works in any case")
        void mixedCaseEmail() throws Exception {
            requestOtp("Mixed.Case@IITM.ac.in").andExpect(status().isOk());
            String code = emailSender.lastCodeFor("mixed.case@iitm.ac.in");

            register("MIXED.case@iitm.AC.in", code).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value("mixed.case@iitm.ac.in"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"Mixed.Case@IITM.ac.in\",\"password\":\"Pass1234!\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Register without ever requesting a code → 400, no account")
        void registerWithoutCode() throws Exception {
            register("nocode@iitm.ac.in", "123456").andExpect(status().isBadRequest());
            assertThat(professorRepository.existsByEmailIgnoreCase("nocode@iitm.ac.in")).isFalse();
        }

        @Test
        @DisplayName("Wrong code → 400 with attempts left, no account")
        void wrongCode() throws Exception {
            String email = "wrongcode@iitm.ac.in";
            requestOtp(email).andExpect(status().isOk());
            String wrong = emailSender.lastCodeFor(email).equals("000000") ? "111111" : "000000";

            register(email, wrong).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Incorrect code. 4 attempts left."));
            assertThat(professorRepository.existsByEmailIgnoreCase(email)).isFalse();
        }

        @Test
        @DisplayName("A code sent to one address can not register a different address")
        void codeBoundToEmail() throws Exception {
            requestOtp("owner@iitm.ac.in").andExpect(status().isOk());
            String code = emailSender.lastCodeFor("owner@iitm.ac.in");

            register("attacker@iitm.ac.in", code).andExpect(status().isBadRequest());
            assertThat(professorRepository.existsByEmailIgnoreCase("attacker@iitm.ac.in")).isFalse();
        }

        @Test
        @DisplayName("A code can only be used once")
        void codeSingleUse() throws Exception {
            String email = "once@iitm.ac.in";
            requestOtp(email).andExpect(status().isOk());
            String code = emailSender.lastCodeFor(email);
            register(email, code).andExpect(status().isCreated());

            // Same email again → already registered (409) before the code is even checked
            register(email, code).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Student smail address is refused at both steps")
        void studentAddressRefused() throws Exception {
            requestOtp("cs24b001@smail.iitm.ac.in").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Student")));
            register("cs24b001@smail.iitm.ac.in", "123456").andExpect(status().isBadRequest());
            assertThat(emailSender.hasEmailFor("cs24b001@smail.iitm.ac.in")).isFalse();
        }

        @Test
        @DisplayName("Non-IITM address is refused and no email is sent")
        void otherDomainRefused() throws Exception {
            requestOtp("someone@gmail.com").andExpect(status().isBadRequest());
            requestOtp("someone@iitm.ac.in.evil.com").andExpect(status().isBadRequest());
            assertThat(emailSender.hasEmailFor("someone@gmail.com")).isFalse();
        }

        @Test
        @DisplayName("Requesting a code for an already-registered email → 409, no email")
        void alreadyRegisteredGetsNoCode() throws Exception {
            createProfessorAndLogin("existing@iitm.ac.in", "Pass1234!");
            requestOtp("existing@iitm.ac.in").andExpect(status().isConflict());
            assertThat(emailSender.hasEmailFor("existing@iitm.ac.in")).isFalse();
        }

        @Test
        @DisplayName("Requesting again within the cooldown → 429 with Retry-After")
        void resendCooldown() throws Exception {
            requestOtp("cooldown@iitm.ac.in").andExpect(status().isOk());
            requestOtp("cooldown@iitm.ac.in").andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));
        }

        @Test
        @DisplayName("Old invite-code style registration (no otp field) → 400")
        void oldInviteStyleRejected() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"inviteCode\":\"eyJhbGciOi.fake.code\",\"email\":\"old@iitm.ac.in\"," +
                                    "\"password\":\"Pass1234!\",\"fullName\":\"Old Flow\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("The removed admin invite endpoint is no longer public → 401")
        void adminInviteEndpointGone() throws Exception {
            mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"changeme-admin-secret-2026\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 6: Scan Token Short-Expiry Enforcement
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 6 — Scan Token Expiry")
    class ScanTokenExpiry {

        @Test
        @DisplayName("Scan token expires in ~15 seconds (not 24 hours)")
        void scanTokenHasShortExpiry() {
            UUID sessionId = UUID.randomUUID();
            String scanToken = jwtUtil.generateScanToken(sessionId);

            // Token must not be expired right after generation
            assertThat(jwtUtil.isTokenExpired(scanToken)).isFalse();

            // The expiry must be ≤ 20 seconds from now (it's 15s, allow 5s of slack)
            Instant exp = jwtUtil.validateToken(scanToken).getExpiration().toInstant();
            assertThat(exp).isBefore(Instant.now().plus(20, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("An immediately-expired scan token is invalid")
        void immediatelyExpiredScanToken_isInvalid() {
            // Build a scan token with expiry in the past using JJWT directly
            javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                    "super-secret-key-for-qr-attendance-system-2026-change-in-prod"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String expiredScanToken = io.jsonwebtoken.Jwts.builder()
                    .subject(UUID.randomUUID().toString())
                    .claim("type", "SCAN")
                    .issuedAt(new java.util.Date(System.currentTimeMillis() - 30000))
                    .expiration(new java.util.Date(System.currentTimeMillis() - 1))
                    .signWith(key)
                    .compact();
            assertThat(jwtUtil.isTokenExpired(expiredScanToken)).isTrue();
        }

        @Test
        @DisplayName("QR endpoint on a CLOSED session → 409, not 200")
        void closedSession_qrEndpoint_returns409() throws Exception {
            String jwt = createProfessorAndLogin("closed@test.com", "Pass1234!");
            UUID courseId = createCourse(jwt);
            UUID profId = jwtUtil.extractProfessorId(jwt);
            UUID sessionId = createExpiredSession(courseId, profId);

            mockMvc.perform(get("/api/sessions/" + sessionId + "/qr")
                            .header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isConflict());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 7: Sensitive Data Never Leaked in Responses
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 7 — Sensitive Data Not Leaked")
    class SensitiveDataNotLeaked {

        @Test
        @DisplayName("Login response does NOT contain passwordHash")
        void loginResponse_noPasswordHash() throws Exception {
            String email = "noleak1@test.com";
            createProfessorAndLogin(email, "Pass1234!");

            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"Pass1234!\"}"))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("passwordHash");
            assertThat(body).doesNotContain("$2a$");
            assertThat(body).doesNotContain("Pass1234!");
        }

        @Test
        @DisplayName("Register response does NOT contain passwordHash")
        void registerResponse_noPasswordHash() throws Exception {
            mockMvc.perform(post("/api/auth/otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"noleak2@iitm.ac.in\"}"))
                    .andExpect(status().isOk());
            String code = emailSender.lastCodeFor("noleak2@iitm.ac.in");

            MvcResult result = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"otp\":\"" + code + "\"," +
                                    "\"email\":\"noleak2@iitm.ac.in\"," +
                                    "\"password\":\"Pass1234!\"," +
                                    "\"fullName\":\"Test Prof\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("passwordHash");
            assertThat(body).doesNotContain("$2a$");
            assertThat(body).doesNotContain("Pass1234!");
        }

        @Test
        @DisplayName("GET /api/courses does NOT expose professor passwordHash in nested objects")
        void listCourses_noPasswordLeak() throws Exception {
            String jwt = createProfessorAndLogin("noleak3@test.com", "Pass1234!");
            createCourse(jwt);

            MvcResult result = mockMvc.perform(get("/api/courses")
                            .header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("passwordHash");
            assertThat(body).doesNotContain("$2a$");
        }

        @Test
        @DisplayName("Error responses do NOT contain stack traces or internal paths")
        void errorResponse_noStackTrace() throws Exception {
            // Trigger a 404 and verify no stack trace leaks
            MvcResult result = mockMvc.perform(get("/api/courses/" + UUID.randomUUID())
                            .header("Authorization", "Bearer " +
                                    createProfessorAndLogin("stackleak@test.com", "Pass1234!")))
                    .andExpect(status().isNotFound())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("at com.qrattend");
            assertThat(body).doesNotContain("java.lang");
            assertThat(body).doesNotContain("Exception");
        }

        @Test
        @DisplayName("The verification code is NOT returned by the API — only emailed")
        void otpNotInResponse() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"noleak4@iitm.ac.in\"}"))
                    .andExpect(status().isOk())
                    .andReturn();

            String code = emailSender.lastCodeFor("noleak4@iitm.ac.in");
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain(code);
            assertThat(body).doesNotContain("codeHash");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 8: Error Message Consistency (no info disclosure)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 8 — Error Message Consistency")
    class ErrorMessageConsistency {

        @Test
        @DisplayName("Unknown email and wrong password return identical error message (no user enumeration)")
        void loginError_sameMessageForUnknownEmailAndWrongPassword() throws Exception {
            // Register a professor first
            createProfessorAndLogin("enum@test.com", "Pass1234!");

            MvcResult unknownEmail = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@test.com\",\"password\":\"Pass1234!\"}"))
                    .andReturn();

            MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"enum@test.com\",\"password\":\"WrongPass!\"}"))
                    .andReturn();

            String msg1 = objectMapper.readTree(unknownEmail.getResponse().getContentAsString())
                    .get("message").asText();
            String msg2 = objectMapper.readTree(wrongPassword.getResponse().getContentAsString())
                    .get("message").asText();

            // Both must return the same vague message — prevents user enumeration
            assertThat(msg1).isEqualTo(msg2).isEqualTo("Invalid email or password");
        }

        @Test
        @DisplayName("404 for non-existent UUID does NOT reveal whether it was a course or session")
        void notFound_vagueMessage() throws Exception {
            String jwt = createProfessorAndLogin("vague@test.com", "Pass1234!");
            UUID randomId = UUID.randomUUID();

            MvcResult result = mockMvc.perform(get("/api/courses/" + randomId)
                            .header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isNotFound())
                    .andReturn();

            // Should contain a useful message but not internal table names/SQL
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("SQL");
            assertThat(body).doesNotContain("hibernate");
            assertThat(body).doesNotContain("table");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 9: Session Doubts Access Control
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 9 — Session Doubts Access Control")
    class SessionDoubtsAccess {

        private void postDoubt(UUID sessionId, String question) {
            QrSession session = sessionRepository.findById(sessionId).orElseThrow();
            doubtRepository.save(TestDataFactory.doubt(session, question));
        }

        @Test
        @DisplayName("Owner gets 200 with the session's doubts")
        void ownerCanReadDoubts() throws Exception {
            String jwtA = createProfessorAndLogin("doubtOwner@test.com", "Pass1234!");
            UUID sessionId = createLiveSession(createCourse(jwtA), jwtUtil.extractProfessorId(jwtA));
            postDoubt(sessionId, "What is paging?");
            postDoubt(sessionId, "Is this on the exam?");

            MvcResult result = mockMvc.perform(get("/api/sessions/" + sessionId + "/doubts")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.isArray()).isTrue();
            assertThat(body.size()).isEqualTo(2);
            assertThat(body.findValuesAsText("text"))
                    .containsExactlyInAnyOrder("What is paging?", "Is this on the exam?");
            assertThat(body.findValuesAsText("sessionId")).containsOnly(sessionId.toString());
        }

        @Test
        @DisplayName("Owner gets 200 with an empty array when no doubts were posted")
        void ownerGetsEmptyArray() throws Exception {
            String jwtA = createProfessorAndLogin("doubtEmpty@test.com", "Pass1234!");
            UUID sessionId = createLiveSession(createCourse(jwtA), jwtUtil.extractProfessorId(jwtA));

            mockMvc.perform(get("/api/sessions/" + sessionId + "/doubts")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        }

        @Test
        @DisplayName("Owner can still read doubts after the session has expired")
        void ownerCanReadDoubtsOfExpiredSession() throws Exception {
            String jwtA = createProfessorAndLogin("doubtExpired@test.com", "Pass1234!");
            UUID sessionId = createExpiredSession(createCourse(jwtA), jwtUtil.extractProfessorId(jwtA));
            postDoubt(sessionId, "Late question");

            mockMvc.perform(get("/api/sessions/" + sessionId + "/doubts")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].text").value("Late question"));
        }

        @Test
        @DisplayName("Doubts from the owner's other sessions are not included")
        void doubtsAreScopedToTheRequestedSession() throws Exception {
            String jwtA = createProfessorAndLogin("doubtScope@test.com", "Pass1234!");
            UUID profA = jwtUtil.extractProfessorId(jwtA);
            UUID courseId = createCourse(jwtA);
            UUID session1 = createLiveSession(courseId, profA);
            UUID session2 = createExpiredSession(courseId, profA);
            postDoubt(session1, "Session 1 doubt");
            postDoubt(session2, "Session 2 doubt");

            mockMvc.perform(get("/api/sessions/" + session1 + "/doubts")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].text").value("Session 1 doubt"));
        }

        @Test
        @DisplayName("Professor B cannot read Professor A's session doubts → 403, nothing leaked")
        void otherProfessorGets403() throws Exception {
            String jwtA = createProfessorAndLogin("doubtA@test.com", "Pass1234!");
            String jwtB = createProfessorAndLogin("doubtB@test.com", "Pass1234!");
            UUID sessionA = createLiveSession(createCourse(jwtA), jwtUtil.extractProfessorId(jwtA));
            postDoubt(sessionA, "Private question from class A");

            MvcResult result = mockMvc.perform(get("/api/sessions/" + sessionA + "/doubts")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Private question from class A");
        }

        @Test
        @DisplayName("Non-existent session → 404")
        void nonExistentSessionReturns404() throws Exception {
            String jwtA = createProfessorAndLogin("doubt404@test.com", "Pass1234!");

            mockMvc.perform(get("/api/sessions/" + UUID.randomUUID() + "/doubts")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("No Authorization header → 401")
        void noTokenReturns401() throws Exception {
            mockMvc.perform(get("/api/sessions/" + UUID.randomUUID() + "/doubts"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Student SCAN token for that session → 403 (needs ROLE_PROFESSOR)")
        void scanTokenReturns403() throws Exception {
            String jwtA = createProfessorAndLogin("doubtScan@test.com", "Pass1234!");
            UUID sessionId = createLiveSession(createCourse(jwtA), jwtUtil.extractProfessorId(jwtA));
            postDoubt(sessionId, "Question");

            mockMvc.perform(get("/api/sessions/" + sessionId + "/doubts")
                            .header("Authorization", "Bearer " + jwtUtil.generateScanToken(sessionId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Student ATTENDANCE token (the one used to post doubts) → 403")
        void attendanceTokenReturns403() throws Exception {
            String jwtA = createProfessorAndLogin("doubtAtt@test.com", "Pass1234!");
            UUID sessionId = createLiveSession(createCourse(jwtA), jwtUtil.extractProfessorId(jwtA));
            QrSession session = sessionRepository.findById(sessionId).orElseThrow();
            Attendance att = attendanceRepository.save(
                    TestDataFactory.pendingAttendance(session, "CS24B001"));
            postDoubt(sessionId, "Question");

            mockMvc.perform(get("/api/sessions/" + sessionId + "/doubts")
                            .header("Authorization", "Bearer " + jwtUtil.generateAttendanceToken(att.getId())))
                    .andExpect(status().isForbidden());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GROUP 10: Cross-Professor Isolation — remaining endpoints
    // ═══════════════════════════════════════════════════════════════

    /**
     * Complements Group 4: every professor endpoint not covered there is attacked by
     * Professor B against Professor A's data. Each test asserts both the 403 and,
     * for write endpoints, that A's data is left untouched.
     */
    @Nested
    @DisplayName("Group 10 — Cross-Professor Isolation (remaining endpoints)")
    class CrossProfessorIsolationRemaining {

        private String jwtA;
        private String jwtB;
        private UUID profA;
        private UUID courseA;
        private UUID sessionA;

        @BeforeEach
        void setUpTwoProfessors() throws Exception {
            jwtA = createProfessorAndLogin("isoA2@test.com", "Pass1234!");
            jwtB = createProfessorAndLogin("isoB2@test.com", "Pass1234!");
            profA = jwtUtil.extractProfessorId(jwtA);
            courseA = createCourse(jwtA);
            sessionA = createLiveSession(courseA, profA);
        }

        private Student enrolInCourseA(String rollNumber) {
            Course course = courseRepository.findById(courseA).orElseThrow();
            return studentRepository.save(TestDataFactory.student(course, rollNumber, "Student " + rollNumber));
        }

        private Attendance pendingInSessionA(String rollNumber) {
            QrSession session = sessionRepository.findById(sessionA).orElseThrow();
            return attendanceRepository.save(TestDataFactory.pendingAttendance(session, rollNumber));
        }

        // ── Courses ─────────────────────────────────────────────────

        @Test
        @DisplayName("B cannot list A's course sessions → 403")
        void cannotListCourseSessions() throws Exception {
            mockMvc.perform(get("/api/courses/" + courseA + "/sessions")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());
        }

        // ── Students ────────────────────────────────────────────────

        @Test
        @DisplayName("B cannot delete a student from A's course → 403, student still exists")
        void cannotDeleteStudent() throws Exception {
            Student student = enrolInCourseA("CS24B101");

            mockMvc.perform(delete("/api/students/" + student.getId())
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());

            assertThat(studentRepository.findById(student.getId())).isPresent();
        }

        // ── Sessions ────────────────────────────────────────────────

        @Test
        @DisplayName("B cannot start a session on A's course → 403, no session created")
        void cannotCreateSessionOnCourse() throws Exception {
            long before = sessionRepository.findByCourseId(courseA).size();

            mockMvc.perform(post("/api/sessions")
                            .header("Authorization", "Bearer " + jwtB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"courseId\":\"" + courseA + "\",\"durationSeconds\":120}"))
                    .andExpect(status().isForbidden());

            assertThat(sessionRepository.findByCourseId(courseA)).hasSize((int) before);
        }

        @Test
        @DisplayName("B cannot get the QR scan URL for A's session → 403, no URL leaked")
        void cannotGetQrData() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/sessions/" + sessionA + "/qr-data")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString()).doesNotContain("/scan?token=");
        }

        @Test
        @DisplayName("B cannot close A's session → 403, session stays open")
        void cannotCloseSession() throws Exception {
            mockMvc.perform(patch("/api/sessions/" + sessionA + "/close")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden());

            assertThat(sessionRepository.findById(sessionA).orElseThrow().getClosedAt()).isNull();
        }

        @Test
        @DisplayName("B cannot extend A's session → 403, expiry unchanged")
        void cannotExtendSession() throws Exception {
            QrSession before = sessionRepository.findById(sessionA).orElseThrow();
            Instant expiresBefore = before.getExpiresAt();
            int extendedBefore = before.getExtendedCount();

            mockMvc.perform(patch("/api/sessions/" + sessionA + "/extend")
                            .header("Authorization", "Bearer " + jwtB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"additionalSeconds\":300}"))
                    .andExpect(status().isForbidden());

            QrSession after = sessionRepository.findById(sessionA).orElseThrow();
            assertThat(after.getExpiresAt()).isEqualTo(expiresBefore);
            assertThat(after.getExtendedCount()).isEqualTo(extendedBefore);
        }

        // ── Attendance ──────────────────────────────────────────────

        @Test
        @DisplayName("B cannot list attendance for A's session → 403, no records leaked")
        void cannotListAttendance() throws Exception {
            pendingInSessionA("CS24B102");

            MvcResult result = mockMvc.perform(get("/api/sessions/" + sessionA + "/attendance")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString()).doesNotContain("CS24B102");
        }

        @Test
        @DisplayName("B cannot mark manual attendance in A's session → 403, no record created")
        void cannotAddManualAttendance() throws Exception {
            enrolInCourseA("CS24B103");

            mockMvc.perform(post("/api/sessions/" + sessionA + "/attendance/manual")
                            .header("Authorization", "Bearer " + jwtB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rollNumber\":\"CS24B103\",\"reason\":\"forged\"}"))
                    .andExpect(status().isForbidden());

            assertThat(attendanceRepository.findBySessionIdAndRollNumber(sessionA, "CS24B103")).isEmpty();
        }

        @Test
        @DisplayName("B cannot override an attendance record in A's session → 403, status unchanged")
        void cannotOverrideAttendance() throws Exception {
            Attendance att = pendingInSessionA("CS24B104");

            mockMvc.perform(patch("/api/sessions/attendance/" + att.getId() + "/override")
                            .header("Authorization", "Bearer " + jwtB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CONFIRMED\",\"reason\":\"forged\"}"))
                    .andExpect(status().isForbidden());

            Attendance after = attendanceRepository.findById(att.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AttendanceStatus.PENDING);
            assertThat(after.getManuallyAdded()).isFalse();
            assertThat(after.getOverrideReason()).isNull();
        }

        // ── Reports ─────────────────────────────────────────────────

        @Test
        @DisplayName("B cannot download the Excel report for A's session → 403, no file")
        void cannotDownloadSessionReport() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/reports/sessions/" + sessionA + "/excel")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden())
                    .andReturn();

            assertThat(result.getResponse().getHeader("Content-Disposition")).isNull();
        }

        @Test
        @DisplayName("B cannot download the course report for A's course → 403, no file")
        void cannotDownloadCourseReport() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/reports/courses/" + courseA + "/excel")
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden())
                    .andReturn();

            assertThat(result.getResponse().getHeader("Content-Disposition")).isNull();
        }

        @Test
        @DisplayName("B cannot view A's course attendance report → 403, no student data leaked")
        void cannotViewCourseReport() throws Exception {
            enrolInCourseA("CS24B107");

            MvcResult result = mockMvc.perform(get("/api/reports/courses/" + courseA)
                            .header("Authorization", "Bearer " + jwtB))
                    .andExpect(status().isForbidden())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("CS24B107")
                    .doesNotContain("Student CS24B107");
        }

        @Test
        @DisplayName("Course attendance report without a token → 401")
        void courseReportWithoutTokenIs401() throws Exception {
            mockMvc.perform(get("/api/reports/courses/" + courseA))
                    .andExpect(status().isUnauthorized());
        }

        // ── Positive control ────────────────────────────────────────

        @Test
        @DisplayName("Control: A can use the same endpoints on their own data (403s above are ownership, not breakage)")
        void ownerCanUseTheSameEndpoints() throws Exception {
            enrolInCourseA("CS24B105");
            Attendance att = pendingInSessionA("CS24B106");

            mockMvc.perform(get("/api/courses/" + courseA + "/sessions")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/sessions/" + sessionA + "/qr-data")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/sessions/" + sessionA + "/attendance")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/sessions/" + sessionA + "/attendance/manual")
                            .header("Authorization", "Bearer " + jwtA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rollNumber\":\"CS24B105\",\"reason\":\"battery died\"}"))
                    .andExpect(status().is2xxSuccessful());
            mockMvc.perform(patch("/api/sessions/attendance/" + att.getId() + "/override")
                            .header("Authorization", "Bearer " + jwtA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CONFIRMED\",\"reason\":\"verified\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/reports/sessions/" + sessionA + "/excel")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/reports/courses/" + courseA + "/excel")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/reports/courses/" + courseA)
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/sessions/" + sessionA + "/extend")
                            .header("Authorization", "Bearer " + jwtA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"additionalSeconds\":60}"))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/sessions/" + sessionA + "/close")
                            .header("Authorization", "Bearer " + jwtA))
                    .andExpect(status().isOk());
        }
    }
}
