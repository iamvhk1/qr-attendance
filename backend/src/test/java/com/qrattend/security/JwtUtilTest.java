package com.qrattend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtUtil}.
 *
 * <p>These are pure unit tests — no Spring context is loaded.
 * JwtUtil is instantiated directly with test values.</p>
 */
class JwtUtilTest {

    /**
     * A 64-character Base64 string ≥ 256 bits — satisfies HMAC-SHA256's
     * minimum key length.
     */
    private static final String TEST_SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdW5pdC10ZXN0cy0yMDI2LW11c3QtYmUtMjU2LWJpdHM=";

    /** 24 hours in milliseconds. */
    private static final long LOGIN_EXPIRATION_MS = 86_400_000L;

    /** 15 seconds in milliseconds. */
    private static final long SCAN_EXPIRATION_MS = 15_000L;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(TEST_SECRET, LOGIN_EXPIRATION_MS, SCAN_EXPIRATION_MS, 172_800_000L);
    }

    // ═══════════════════════════════════════════════════════════
    //  LOGIN TOKEN
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Login Token")
    class LoginTokenTests {

        private final UUID professorId = UUID.randomUUID();
        private final String email = "boby@iitm.ac.in";

        @Test
        @DisplayName("generateLoginToken produces a non-blank JWT string")
        void generatesNonBlankToken() {
            String token = jwtUtil.generateLoginToken(professorId, email);
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("extractProfessorId returns the correct professor UUID")
        void extractProfessorId() {
            String token = jwtUtil.generateLoginToken(professorId, email);
            UUID extracted = jwtUtil.extractProfessorId(token);
            assertThat(extracted).isEqualTo(professorId);
        }

        @Test
        @DisplayName("extractEmail returns the correct email")
        void extractEmail() {
            String token = jwtUtil.generateLoginToken(professorId, email);
            String extracted = jwtUtil.extractEmail(token);
            assertThat(extracted).isEqualTo(email);
        }

        @Test
        @DisplayName("validateToken returns claims with correct subject and type")
        void validateTokenReturnsClaims() {
            String token = jwtUtil.generateLoginToken(professorId, email);
            Claims claims = jwtUtil.validateToken(token);

            assertThat(claims.getSubject()).isEqualTo(professorId.toString());
            assertThat(claims.get("type", String.class)).isEqualTo("LOGIN");
            assertThat(claims.get("email", String.class)).isEqualTo(email);
            assertThat(claims.getIssuedAt()).isNotNull();
            assertThat(claims.getExpiration()).isNotNull();
        }

        @Test
        @DisplayName("isLoginToken returns true for a login token")
        void isLoginTokenTrue() {
            String token = jwtUtil.generateLoginToken(professorId, email);
            assertThat(jwtUtil.isLoginToken(token)).isTrue();
        }

        @Test
        @DisplayName("isScanToken returns false for a login token")
        void isScanTokenFalse() {
            String token = jwtUtil.generateLoginToken(professorId, email);
            assertThat(jwtUtil.isScanToken(token)).isFalse();
        }

        @Test
        @DisplayName("isTokenExpired returns false for a fresh login token")
        void freshTokenNotExpired() {
            String token = jwtUtil.generateLoginToken(professorId, email);
            assertThat(jwtUtil.isTokenExpired(token)).isFalse();
        }

        @Test
        @DisplayName("extractSessionId throws IllegalStateException for a LOGIN token")
        void extractSessionIdThrowsForLoginToken() {
            String token = jwtUtil.generateLoginToken(professorId, email);
            assertThatThrownBy(() -> jwtUtil.extractSessionId(token))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SCAN");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  SCAN TOKEN
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scan Token")
    class ScanTokenTests {

        private final UUID sessionId = UUID.randomUUID();

        @Test
        @DisplayName("generateScanToken produces a non-blank JWT string")
        void generatesNonBlankToken() {
            String token = jwtUtil.generateScanToken(sessionId);
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("extractSessionId returns the correct session UUID")
        void extractSessionId() {
            String token = jwtUtil.generateScanToken(sessionId);
            UUID extracted = jwtUtil.extractSessionId(token);
            assertThat(extracted).isEqualTo(sessionId);
        }

        @Test
        @DisplayName("validateToken returns claims with correct subject and type")
        void validateTokenReturnsClaims() {
            String token = jwtUtil.generateScanToken(sessionId);
            Claims claims = jwtUtil.validateToken(token);

            assertThat(claims.getSubject()).isEqualTo(sessionId.toString());
            assertThat(claims.get("type", String.class)).isEqualTo("SCAN");
            assertThat(claims.get("email", String.class)).isNull();
        }

        @Test
        @DisplayName("isScanToken returns true for a scan token")
        void isScanTokenTrue() {
            String token = jwtUtil.generateScanToken(sessionId);
            assertThat(jwtUtil.isScanToken(token)).isTrue();
        }

        @Test
        @DisplayName("isLoginToken returns false for a scan token")
        void isLoginTokenFalse() {
            String token = jwtUtil.generateScanToken(sessionId);
            assertThat(jwtUtil.isLoginToken(token)).isFalse();
        }

        @Test
        @DisplayName("isTokenExpired returns false for a fresh scan token")
        void freshTokenNotExpired() {
            String token = jwtUtil.generateScanToken(sessionId);
            assertThat(jwtUtil.isTokenExpired(token)).isFalse();
        }

        @Test
        @DisplayName("extractProfessorId throws IllegalStateException for a SCAN token")
        void extractProfessorIdThrowsForScanToken() {
            String token = jwtUtil.generateScanToken(sessionId);
            assertThatThrownBy(() -> jwtUtil.extractProfessorId(token))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LOGIN");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  EXPIRATION & INVALID TOKENS
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Expiration & Invalid Tokens")
    class ExpirationAndInvalidTests {

        @Test
        @DisplayName("A token with 0ms expiration is immediately expired")
        void zeroExpirationToken() {
            // Create a JwtUtil instance with 0ms scan expiration
            JwtUtil zeroExpiry = new JwtUtil(TEST_SECRET, LOGIN_EXPIRATION_MS, 0L, 172_800_000L);
            String token = zeroExpiry.generateScanToken(UUID.randomUUID());

            assertThat(zeroExpiry.isTokenExpired(token)).isTrue();
        }

        @Test
        @DisplayName("validateToken throws JwtException for a tampered token")
        void tamperedTokenThrows() {
            String token = jwtUtil.generateLoginToken(UUID.randomUUID(), "test@iitm.ac.in");
            String tampered = token.substring(0, token.length() - 5) + "XXXXX";

            assertThatThrownBy(() -> jwtUtil.validateToken(tampered))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("validateToken throws JwtException for garbage input")
        void garbageTokenThrows() {
            assertThatThrownBy(() -> jwtUtil.validateToken("not.a.jwt"))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("isTokenExpired returns true for a tampered token")
        void tamperedTokenIsExpired() {
            String token = jwtUtil.generateLoginToken(UUID.randomUUID(), "test@iitm.ac.in");
            String tampered = token.substring(0, token.length() - 5) + "XXXXX";

            assertThat(jwtUtil.isTokenExpired(tampered)).isTrue();
        }

        @Test
        @DisplayName("A token signed with a different secret fails validation")
        void differentSecretFails() {
            JwtUtil otherJwtUtil = new JwtUtil(
                    "YW5vdGhlci1zZWNyZXQta2V5LXRoYXQtaXMtZGlmZmVyZW50LWZyb20tdGhlLW9yaWdpbmFsLWtleQ==",
                    LOGIN_EXPIRATION_MS, SCAN_EXPIRATION_MS, 172_800_000L);

            String token = otherJwtUtil.generateLoginToken(UUID.randomUUID(), "test@iitm.ac.in");

            assertThatThrownBy(() -> jwtUtil.validateToken(token))
                    .isInstanceOf(JwtException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CROSS-TYPE SAFETY
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cross-Type Safety")
    class CrossTypeSafetyTests {

        @Test
        @DisplayName("Using a SCAN token as a login token throws IllegalStateException")
        void scanTokenCannotBeUsedAsLogin() {
            String scanToken = jwtUtil.generateScanToken(UUID.randomUUID());

            assertThatThrownBy(() -> jwtUtil.extractProfessorId(scanToken))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> jwtUtil.extractEmail(scanToken))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Using a LOGIN token as a scan token throws IllegalStateException")
        void loginTokenCannotBeUsedAsScan() {
            String loginToken = jwtUtil.generateLoginToken(UUID.randomUUID(), "test@iitm.ac.in");

            assertThatThrownBy(() -> jwtUtil.extractSessionId(loginToken))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("extractTokenType correctly identifies LOGIN and SCAN tokens")
        void extractTokenType() {
            String loginToken = jwtUtil.generateLoginToken(UUID.randomUUID(), "test@iitm.ac.in");
            String scanToken  = jwtUtil.generateScanToken(UUID.randomUUID());

            assertThat(jwtUtil.extractTokenType(loginToken)).isEqualTo("LOGIN");
            assertThat(jwtUtil.extractTokenType(scanToken)).isEqualTo("SCAN");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  NULL AND EMPTY INPUT HANDLING
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null and Empty Input Handling")
    class NullAndEmptyInputTests {

        @Test
        @DisplayName("validateToken throws IllegalArgumentException for null token")
        void validateToken_null_throwsException() {
            assertThatThrownBy(() -> jwtUtil.validateToken(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("validateToken throws IllegalArgumentException for empty token")
        void validateToken_empty_throwsException() {
            assertThatThrownBy(() -> jwtUtil.validateToken(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("validateToken throws IllegalArgumentException for blank token")
        void validateToken_blank_throwsException() {
            assertThatThrownBy(() -> jwtUtil.validateToken("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("isLoginToken propagates IllegalArgumentException for null input")
        void isLoginToken_null_propagatesIllegalArgumentException() {
            assertThatThrownBy(() -> jwtUtil.isLoginToken(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("generateLoginToken handles null email by storing null in claims")
        void generateLoginToken_nullEmail() {
            String token = jwtUtil.generateLoginToken(UUID.randomUUID(), null);
            String extractedEmail = jwtUtil.extractEmail(token);
            assertThat(extractedEmail).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  TOKEN STRUCTURE AND FORMAT
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Token Structure and Format")
    class TokenStructureTests {

        @Test
        @DisplayName("validateToken throws MalformedJwtException for token with only two parts")
        void validateToken_twoParts_throwsException() {
            String twoPartToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0";
            assertThatThrownBy(() -> jwtUtil.validateToken(twoPartToken))
                    .isInstanceOf(MalformedJwtException.class);
        }

        @Test
        @DisplayName("validateToken rejects unsigned token (alg: none)")
        void validateToken_unsigned_throwsException() {
            // JJWT rejects 'alg: none' with UnsupportedJwtException (a subclass of JwtException)
            String unsignedToken = "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxMjMifQ.";
            assertThatThrownBy(() -> jwtUtil.validateToken(unsignedToken))
                    .isInstanceOf(JwtException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  EXPIRATION BOUNDARY CONDITIONS
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Expiration Boundary Conditions")
    class ExpirationBoundaryTests {

        @Test
        @DisplayName("generateLoginToken with negative expiration creates an immediately expired token")
        void negativeExpiration_createsExpiredToken() {
            JwtUtil localJwtUtil = new JwtUtil(TEST_SECRET, -3600000L, SCAN_EXPIRATION_MS, 172_800_000L);
            String token = localJwtUtil.generateLoginToken(UUID.randomUUID(), "test@test.com");

            assertThat(localJwtUtil.isTokenExpired(token)).isTrue();
        }

        @Test
        @DisplayName("generateScanToken with negative expiration creates an immediately expired token")
        void negativeExpirationScan_createsExpiredToken() {
            JwtUtil localJwtUtil = new JwtUtil(TEST_SECRET, LOGIN_EXPIRATION_MS, -1000L, 172_800_000L);
            String token = localJwtUtil.generateScanToken(UUID.randomUUID());

            assertThat(localJwtUtil.isTokenExpired(token)).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CLAIM INTEGRITY AND DATA PRESERVATION
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Claim Integrity and Edge-Case Data")
    class ClaimIntegrityTests {

        @Test
        @DisplayName("Timestamps for issuedAt and expiration are accurately set near current time")
        void tokenTimestamps_areAccurate() {
            Instant beforeGeneration = Instant.now();
            String token = jwtUtil.generateLoginToken(UUID.randomUUID(), "time@test.com");

            Date iat = jwtUtil.validateToken(token).getIssuedAt();
            Date exp = jwtUtil.validateToken(token).getExpiration();

            assertThat(iat.toInstant()).isCloseTo(beforeGeneration, within(2, ChronoUnit.SECONDS));
            assertThat(exp.toInstant()).isCloseTo(beforeGeneration.plusMillis(LOGIN_EXPIRATION_MS), within(2, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("Emails with special characters (unicode, plus addressing) are perfectly preserved")
        void specialCharactersInEmail_arePreserved() {
            String complexEmail = "test+alias.name_123!ñ@sub.domain.co.uk";
            String token = jwtUtil.generateLoginToken(UUID.randomUUID(), complexEmail);

            assertThat(jwtUtil.extractEmail(token)).isEqualTo(complexEmail);
        }

        @Test
        @DisplayName("Extremely long email strings are preserved without truncation")
        void extremelyLongEmail_isPreserved() {
            String longEmail = "a".repeat(250) + "@domain.com";
            String token = jwtUtil.generateLoginToken(UUID.randomUUID(), longEmail);

            assertThat(jwtUtil.extractEmail(token)).isEqualTo(longEmail);
        }

        @Test
        @DisplayName("Validating the same token multiple times returns identical claims (Idempotency)")
        void validateToken_isIdempotent() {
            String token = jwtUtil.generateScanToken(UUID.randomUUID());

            String subject1 = jwtUtil.validateToken(token).getSubject();
            String subject2 = jwtUtil.validateToken(token).getSubject();

            assertThat(subject1).isEqualTo(subject2);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CONSTRUCTOR AND CRYPTO VALIDATION
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor and Crypto Validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Constructor throws WeakKeyException if the secret key is less than 256 bits")
        void shortSecretKey_throwsWeakKeyException() {
            String shortSecret = "1234567890123456789012345678901"; // 31 bytes = 248 bits

            assertThatThrownBy(() -> new JwtUtil(shortSecret, LOGIN_EXPIRATION_MS, SCAN_EXPIRATION_MS, 172_800_000L))
                    .isInstanceOf(WeakKeyException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CONCURRENCY AND THREAD SAFETY
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Concurrency and Thread Safety")
    class ConcurrencyTests {

        @Test
        @DisplayName("JwtUtil can safely generate and validate tokens across multiple threads")
        void threadSafety_concurrentTokenGeneration() {
            UUID testProfessorId = UUID.randomUUID();
            String testEmail = "concurrent@test.com";

            List<String> generatedTokens = new CopyOnWriteArrayList<>();

            IntStream.range(0, 1000).parallel().forEach(i -> {
                String token = jwtUtil.generateLoginToken(testProfessorId, testEmail);
                generatedTokens.add(token);
            });

            assertThat(generatedTokens).hasSize(1000);

            generatedTokens.parallelStream().forEach(token -> {
                assertThat(jwtUtil.extractProfessorId(token)).isEqualTo(testProfessorId);
                assertThat(jwtUtil.extractEmail(token)).isEqualTo(testEmail);
                assertThat(jwtUtil.isLoginToken(token)).isTrue();
            });
        }
    }
}
