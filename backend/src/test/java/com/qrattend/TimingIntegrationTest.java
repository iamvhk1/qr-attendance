package com.qrattend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.qrattend.repository.CourseRepository;
import com.qrattend.repository.ProfessorRepository;
import com.qrattend.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Timing integration tests for the rolling QR mechanism and session expiry window.
 *
 * <h2>What this proves</h2>
 * <ul>
 *   <li>A fresh QR scan token passes HTTP authentication; an expired one is hard-rejected (401).</li>
 *   <li>Two consecutive QR fetches embed <em>different</em> JWTs — the roll is real.</li>
 *   <li>A session created with {@code durationSeconds=2} is LIVE immediately and CLOSED after 3s.</li>
 *   <li>The QR endpoint ({@code GET /qr}) returns 409 once the session window closes.</li>
 * </ul>
 *
 * <h2>Why scan-expiration-ms=2000?</h2>
 * <p>The production value is 15 000ms (15s). Waiting 16s per test makes the suite impractically
 * slow. Overriding to 2 000ms lets us prove the real expiry path in under 3s per test while
 * keeping the mechanism identical — the timer fires, JJWT throws {@code ExpiredJwtException},
 * the filter skips context population, and Spring Security returns 401.</p>
 *
 * <h2>Why NOT mock the clock?</h2>
 * <p>Mocking would test that our test mock works, not that JJWT's expiry, our filter, Spring
 * Security, and the session {@code isClosed()} check all integrate correctly at real wall-clock
 * time. These tests validate the <em>wiring</em>, not just the logic.</p>
 */
@SpringBootTest(properties = {
        // ── Override scan token lifetime to 2 s so timing tests finish in < 5 s ──
        // The real expiry path (ExpiredJwtException → filter skips → 401) is unchanged.
        "app.jwt.scan-expiration-ms=2000"
})
@AutoConfigureMockMvc
@DisplayName("Timing Tests — Rolling QR (2 s token) and Session Window")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TimingIntegrationTest {

    @Autowired private MockMvc       mockMvc;
    @Autowired private ObjectMapper  objectMapper;
    @Autowired private JwtUtil       jwtUtil;

    // Unique credentials — will not clash with SecurityIntegrationTest or PersistenceIntegrationTest
    private static final String EMAIL    = "timing.prof@test.com";
    private static final String PASSWORD = "Timing1234!";

    // Shared state across the ordered test sequence
    private static String cachedJwt;
    private static UUID   persistedCourseId;
    private static UUID   persistedSessionId; // 10-second session for Parts 1-2

    // ── Global cleanup ────────────────────────────────────────────────────────

    @AfterAll
    static void cleanup(@Autowired ProfessorRepository professorRepository,
                        @Autowired CourseRepository     courseRepository) {
        professorRepository.findByEmail(EMAIL).ifPresent(prof -> {
            courseRepository.findByProfessorId(prof.getId())
                    .forEach(courseRepository::delete);
            professorRepository.delete(prof);
        });
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /**
     * Returns a cached professor JWT, registering the professor first if needed.
     * Self-bootstrapping so individual tests can run in isolation.
     */
    private String jwt() throws Exception {
        if (cachedJwt != null) return cachedJwt;

        MvcResult loginAttempt = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();

        if (loginAttempt.getResponse().getStatus() == 401) {
            // Professor doesn't exist yet — register first
            MvcResult inviteResult = mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"changeme-admin-secret-2026\"}"))
                    .andExpect(status().isOk()).andReturn();
            String invite = objectMapper.readTree(
                    inviteResult.getResponse().getContentAsString()).get("inviteCode").asText();

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"inviteCode\":\"" + invite + "\",\"email\":\"" + EMAIL
                                    + "\",\"password\":\"" + PASSWORD
                                    + "\",\"fullName\":\"Dr. Timer\"}"))
                    .andExpect(status().isCreated());

            loginAttempt = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
        }

        cachedJwt = objectMapper.readTree(loginAttempt.getResponse().getContentAsString())
                .get("token").asText();
        return cachedJwt;
    }

    /**
     * Decodes a QR code PNG byte-array and returns the embedded URL string.
     *
     * <p>Uses ZXing (the same library that generated the QR) to decode it, proving the
     * produced PNG is actually a scannable QR and not just random bytes.</p>
     *
     * @throws com.google.zxing.NotFoundException if the bytes do not contain a readable QR code
     */
    private String decodeQrPng(byte[] pngBytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertThat(image).as("PNG bytes must decode to a valid BufferedImage").isNotNull();
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new MultiFormatReader().decode(bitmap).getText();
    }

    /**
     * Extracts the JWT from a scan URL of the form:
     * {@code http://localhost:5173/scan?token=<jwt>}
     */
    private String extractScanToken(String scanUrl) {
        int idx = scanUrl.indexOf("?token=");
        assertThat(idx).as("Scan URL must contain '?token=' parameter").isGreaterThan(0);
        return scanUrl.substring(idx + "?token=".length());
    }
    /**
     * Ensures a course and a 10-second session exist, creating them on demand.
     * Called at the top of every test that needs {@code persistedCourseId} or
     * {@code persistedSessionId}.
     */
    private void ensureSetup() throws Exception {
        // Create course if not yet done
        if (persistedCourseId == null) {
            MvcResult result = mockMvc.perform(post("/api/courses")
                            .header("Authorization", "Bearer " + jwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Timing Course\",\"code\":\"TMG101\",\"semester\":\"2026\"}"))
                    .andExpect(status().isCreated()).andReturn();
            persistedCourseId = UUID.fromString(
                    objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        }
        // Create session if not yet done
        if (persistedSessionId == null) {
            MvcResult result = mockMvc.perform(post("/api/sessions")
                            .header("Authorization", "Bearer " + jwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"courseId\":\"" + persistedCourseId + "\",\"durationSeconds\":10}"))
                    .andExpect(status().isCreated()).andReturn();
            persistedSessionId = UUID.fromString(
                    objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PART 1 — QR PNG Validity (the image itself)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test @Order(10)
    @DisplayName("QR-1a: GET /qr returns Content-Type: image/png with valid PNG magic bytes")
    void qr_returnsValidPngBytes() throws Exception {
        ensureSetup();
        byte[] pngBytes = mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(pngBytes).hasSizeGreaterThan(100);

        // PNG magic signature: 0x89 'P' 'N' 'G' (first 4 bytes)
        assertThat(pngBytes[0]).as("PNG magic byte 0").isEqualTo((byte) 0x89);
        assertThat(pngBytes[1]).as("PNG magic byte 1 ('P')").isEqualTo((byte) 0x50);
        assertThat(pngBytes[2]).as("PNG magic byte 2 ('N')").isEqualTo((byte) 0x4E);
        assertThat(pngBytes[3]).as("PNG magic byte 3 ('G')").isEqualTo((byte) 0x47);
    }

    @Test @Order(11)
    @DisplayName("QR-1b: QR code decodes to 'http://localhost:5173/scan?token=<jwt>' (ZXing round-trip)")
    void qr_decodesToScanUrl() throws Exception {
        ensureSetup();
        byte[] pngBytes = mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // ZXing decodes the QR the same way a student's phone camera would
        String scanUrl = decodeQrPng(pngBytes);
        assertThat(scanUrl).startsWith("http://localhost:5173/scan?token=");

        // The embedded token must be a structurally valid 3-part JWT
        String scanToken = extractScanToken(scanUrl);
        assertThat(scanToken.split("\\.")).hasSize(3);

        // JwtUtil confirms it as a SCAN-type token that has not expired yet
        assertThat(jwtUtil.isScanToken(scanToken))
                .as("Embedded JWT must be classified as SCAN type").isTrue();
        assertThat(jwtUtil.isTokenExpired(scanToken))
                .as("Freshly generated scan token must not be expired").isFalse();
    }

    @Test @Order(12)
    @DisplayName("QR-1c: Scan token subject encodes the session ID (correct session is embedded)")
    void qr_scanTokenSubjectIsSessionId() throws Exception {
        ensureSetup();
        byte[] pngBytes = mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String scanToken = extractScanToken(decodeQrPng(pngBytes));

        // extractSessionId internally validates type=SCAN, then reads the JWT subject
        UUID embeddedSessionId = jwtUtil.extractSessionId(scanToken);
        assertThat(embeddedSessionId)
                .as("Token subject must be the session ID that created the QR")
                .isEqualTo(persistedSessionId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PART 2 — Rolling QR (each fetch produces a fresh, different token)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test @Order(20)
    @DisplayName("QR-2: Two consecutive fetches embed DIFFERENT JWTs (rolling is real, not cached)")
    void qr_consecutiveFetchesProduceDifferentTokens() throws Exception {
        ensureSetup();
        byte[] png1 = mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // Decode and validate token1 immediately — before any sleep — so it is still fresh
        String token1 = extractScanToken(decodeQrPng(png1));
        assertThat(jwtUtil.isScanToken(token1)).as("token1 must be a valid SCAN type").isTrue();
        assertThat(jwtUtil.isTokenExpired(token1)).as("token1 must not be expired yet").isFalse();
        assertThat(jwtUtil.extractSessionId(token1)).as("token1 must encode the correct session")
                .isEqualTo(persistedSessionId);

        // JJWT timestamps (iat/exp) are second-precision (Unix epoch seconds).
        // Two tokens generated within the same second are byte-for-byte identical.
        // Sleep 1100 ms to cross a second boundary, guaranteeing different iat/exp
        // → different HMAC signature → different token → different QR code.
        Thread.sleep(1100);

        byte[] png2 = mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // Decode and validate token2 immediately — it is fresh from the second fetch
        String token2 = extractScanToken(decodeQrPng(png2));
        assertThat(jwtUtil.isScanToken(token2)).as("token2 must be a valid SCAN type").isTrue();
        assertThat(jwtUtil.isTokenExpired(token2)).as("token2 must not be expired yet").isFalse();
        assertThat(jwtUtil.extractSessionId(token2)).as("token2 must encode the correct session")
                .isEqualTo(persistedSessionId);

        // Core assertion: the rolling mechanism produced two distinct tokens
        assertThat(token1).as("Consecutive QR tokens must be different (rolling is real, not cached)")
                .isNotEqualTo(token2);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PART 3 — Scan Token Expiry (2-second tokens, wait 2.5 s)
    //
    //  This is the anti-WhatsApp-share mechanism:
    //  A student who screenshots the QR and shares it gets an already-expired token.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test @Order(30)
    @DisplayName("Token-3a: Fresh scan token passes Spring Security (HTTP auth, not 401)")
    void scanToken_freshPassesHttpAuthentication() throws Exception {
        ensureSetup();
        byte[] pngBytes = mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String scanToken = extractScanToken(decodeQrPng(pngBytes));

        /*
         * POST /api/student/scan requires ROLE_SCAN.
         * The endpoint controller is not yet implemented (future phase), so a
         * successfully authenticated request returns 404 (no handler found).
         * An expired/invalid token is rejected BEFORE reaching the dispatcher → 401.
         *
         * We therefore assert status ≠ 401: the JWT was accepted by the security filter,
         * proving the fresh token grants ROLE_SCAN authority correctly.
         */
        int status = mockMvc.perform(post("/api/student/scan")
                        .header("Authorization", "Bearer " + scanToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"CS001\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("Fresh scan token must pass Spring Security authentication (not 401)")
                .isNotEqualTo(401);
    }

    @Test @Order(31)
    @DisplayName("Token-3b: After 2.5 s, jwtUtil.isTokenExpired() returns true (2 s token)")
    void scanToken_expiredAfter2Seconds_utilConfirms() throws Exception {
        ensureSetup();
        byte[] pngBytes = mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String scanToken = extractScanToken(decodeQrPng(pngBytes));

        // Sanity: fresh token is not expired
        assertThat(jwtUtil.isTokenExpired(scanToken))
                .as("Scan token must be valid immediately after creation").isFalse();

        // Wait past the 2-second window (500 ms buffer for slow CI machines)
        Thread.sleep(2500);

        // Now the token must be expired
        assertThat(jwtUtil.isTokenExpired(scanToken))
                .as("Scan token must be expired after its 2-second window closes").isTrue();
    }

    @Test @Order(32)
    @DisplayName("Token-3c: Expired scan token is REJECTED at HTTP level with 401 (core anti-share test)")
    void scanToken_expiredRejectedAtHttpLevel() throws Exception {
        ensureSetup();
        /*
         * This is the most important timing test:
         *
         * A student photographs the QR at T=0. At T=2.5s (after the 2s window),
         * they try to submit their roll number using the old token.
         * The JwtAuthenticationFilter sees isTokenExpired() == true → skips context
         * population → request proceeds anonymously → Spring Security returns 401.
         *
         * This is what prevents "WhatsApp sharing" attacks where students forward
         * the QR to absent classmates who scan it later.
         *
         * NOTE: We create a dedicated fresh session here rather than reusing
         * persistedSessionId, because by this point (after test-31's 2.5s sleep)
         * the shared 10-second session may already be expired and the QR endpoint
         * would return an error instead of a PNG.
         */
        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":\"" + persistedCourseId + "\",\"durationSeconds\":30}"))
                .andExpect(status().isCreated()).andReturn();
        UUID freshSessionId = UUID.fromString(
                objectMapper.readTree(sessionResult.getResponse().getContentAsString()).get("id").asText());

        byte[] pngBytes = mockMvc.perform(get("/api/sessions/" + freshSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String scanToken = extractScanToken(decodeQrPng(pngBytes));

        // Token is valid immediately
        assertThat(jwtUtil.isTokenExpired(scanToken)).isFalse();

        // Simulate the student waiting / sharing the link after the window closes
        Thread.sleep(2500);

        // Expired token must be hard-rejected — this is the security guarantee
        mockMvc.perform(post("/api/student/scan")
                        .header("Authorization", "Bearer " + scanToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"CS001\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PART 4 — Session Window (natural time-based expiry)
    //
    //  Sessions are created with durationSeconds=2 and tested at T=0 (LIVE)
    //  and T=3s (CLOSED). This mirrors the real 2-minute window.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test @Order(40)
    @DisplayName("Session-4a: Session is LIVE immediately — GET /sessions/{id} returns status=LIVE")
    void session_liveImmediatelyAfterCreation() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":\"" + persistedCourseId + "\",\"durationSeconds\":2}"))
                .andExpect(status().isCreated()).andReturn();

        UUID shortSessionId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        // Status immediately after creation must be LIVE
        mockMvc.perform(get("/api/sessions/" + shortSessionId)
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.id").value(shortSessionId.toString()));
    }

    @Test @Order(41)
    @DisplayName("Session-4b: QR endpoint is accessible (200) during the active session window")
    void session_qrAccessibleDuringWindow() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":\"" + persistedCourseId + "\",\"durationSeconds\":2}"))
                .andExpect(status().isCreated()).andReturn();

        UUID shortSessionId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        // QR must be served immediately (session is live)
        byte[] pngBytes = mockMvc.perform(get("/api/sessions/" + shortSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(pngBytes).hasSizeGreaterThan(100);
    }

    @Test @Order(42)
    @DisplayName("Session-4c: Session status becomes CLOSED after natural expiry (3 s wait for 2 s window)")
    void session_naturallyClosedAfterDuration() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":\"" + persistedCourseId + "\",\"durationSeconds\":2}"))
                .andExpect(status().isCreated()).andReturn();

        UUID shortSessionId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        // T=0 : LIVE
        mockMvc.perform(get("/api/sessions/" + shortSessionId)
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"));

        // Wait past the 2-second window (1s buffer)
        Thread.sleep(3000);

        // T=3s : CLOSED — QrSession.isClosed() returns true because Instant.now() > expiresAt
        mockMvc.perform(get("/api/sessions/" + shortSessionId)
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.id").value(shortSessionId.toString()));
    }

    @Test @Order(43)
    @DisplayName("Session-4d: QR endpoint returns 409 Conflict after session window closes (not 200)")
    void session_expiredWindowQrReturns409() throws Exception {
        /*
         * Once the session window closes, GET /qr must return 409 (SessionClosedException).
         * This prevents the professor from continuing to display an expired QR code
         * whose scan tokens would be immediately rejected anyway.
         */
        MvcResult result = mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":\"" + persistedCourseId + "\",\"durationSeconds\":2}"))
                .andExpect(status().isCreated()).andReturn();

        UUID shortSessionId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        // T=0: QR accessible
        mockMvc.perform(get("/api/sessions/" + shortSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk());

        Thread.sleep(3000);

        // T=3s: QR blocked — session is now closed
        mockMvc.perform(get("/api/sessions/" + shortSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isConflict());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PART 5 — expiresAt correctness
    // ═══════════════════════════════════════════════════════════════════════════

    @Test @Order(50)
    @DisplayName("Session-5: expiresAt in response = createdAt + durationSeconds (within 2s tolerance)")
    void session_expiresAtMatchesDurationSeconds() throws Exception {
        Instant before = Instant.now();

        MvcResult result = mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":\"" + persistedCourseId + "\",\"durationSeconds\":120}"))
                .andExpect(status().isCreated()).andReturn();

        Instant after = Instant.now();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Instant expiresAt = Instant.parse(body.get("expiresAt").asText());

        // Must be ~120s from when we called POST (allow 2s tolerance for slow machines)
        assertThat(expiresAt)
                .as("expiresAt must be at least 118s in the future")
                .isAfter(before.plusSeconds(118));
        assertThat(expiresAt)
                .as("expiresAt must be at most 122s in the future")
                .isBefore(after.plusSeconds(122));
    }
}
