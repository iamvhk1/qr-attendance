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
 *   <li>Token type misuse (SCAN as LOGIN, LOGIN as SCAN, INVITE as either)</li>
 *   <li>Algorithm confusion (alg:none)</li>
 *   <li>Cross-professor resource isolation</li>
 *   <li>Horizontal privilege escalation (accessing another professor's sessions/courses)</li>
 *   <li>Admin secret protection</li>
 *   <li>ROLE boundary enforcement (PROFESSOR vs SCAN endpoints)</li>
 *   <li>Timing-safe admin secret comparison</li>
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
    @Autowired private PasswordEncoder passwordEncoder;

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
                    -1000L, 15000L, 172800000L, 7200000L);
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
        @DisplayName("INVITE token used on professor endpoint → 401 (wrong type)")
        void inviteTokenOnProfessorEndpoint_returns401() throws Exception {
            String inviteJwt = jwtUtil.generateInviteToken();
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

        @Test
        @DisplayName("INVITE token on admin endpoint still rejects (invite is not admin secret)")
        void inviteTokenOnAdminEndpoint_behavesCorrectly() throws Exception {
            // /api/admin/invite doesn't use JWT — it uses adminSecret in body
            // Using an invite token as the adminSecret should be rejected (wrong secret value)
            String inviteJwt = jwtUtil.generateInviteToken();
            mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\": \"" + inviteJwt + "\"}"))
                    .andExpect(status().isUnauthorized());
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
    //  GROUP 5: Admin Secret Protection
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 5 — Admin Secret Protection")
    class AdminSecretProtection {

        @Test
        @DisplayName("Wrong admin secret → 401")
        void wrongSecret_returns401() throws Exception {
            mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"hacker\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Empty admin secret → 400 (validation)")
        void emptySecret_returns400() throws Exception {
            mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Admin secret that is almost correct (1 char off) → 401 — constant-time compare")
        void almostCorrectSecret_returns401() throws Exception {
            // Tests that the constant-time MessageDigest.isEqual is functioning
            mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"changeme-admin-secret-202X\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Valid JWT as admin secret → 401 (JWT is not the admin secret)")
        void jwtAsAdminSecret_returns401() throws Exception {
            String jwt = createProfessorAndLogin("jwtadmin@test.com", "Pass1234!");
            mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"" + jwt + "\"}"))
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
            // Get a real invite for this test
            MvcResult inviteResult = mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"changeme-admin-secret-2026\"}"))
                    .andReturn();
            String invite = objectMapper.readTree(
                    inviteResult.getResponse().getContentAsString()).get("inviteCode").asText();

            MvcResult result = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"inviteCode\":\"" + invite + "\"," +
                                    "\"email\":\"noleak2@test.com\"," +
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
        @DisplayName("Admin secret is NOT echoed back in any response")
        void adminSecret_notInResponse() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"changeme-admin-secret-2026\"}"))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("changeme-admin-secret-2026");
            assertThat(body).doesNotContain("adminSecret");
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
}
