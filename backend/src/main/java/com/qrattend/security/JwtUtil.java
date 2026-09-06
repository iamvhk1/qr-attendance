package com.qrattend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Utility class for creating and validating JSON Web Tokens.
 *
 * <p>Two token types are issued:</p>
 * <ul>
 *   <li><b>LOGIN</b> — 24-hour token for professor authentication.
 *       Subject = professorId, claim "email" = professor email.</li>
 *   <li><b>SCAN</b> — 15-second token embedded in QR code URLs.
 *       Subject = sessionId. No email claim.</li>
 * </ul>
 *
 * <p>Both token types carry a {@code "type"} claim ("LOGIN" or "SCAN") so
 * that a scan token can never be misused as a login token and vice-versa.</p>
 */
@Component
public class JwtUtil {

    /** Custom claim key that distinguishes LOGIN from SCAN tokens. */
    private static final String CLAIM_TYPE  = "type";
    private static final String CLAIM_EMAIL = "email";

    private static final String TYPE_LOGIN = "LOGIN";
    private static final String TYPE_SCAN  = "SCAN";
    private static final String TYPE_INVITE = "INVITE";

    private final SecretKey signingKey;
    private final long loginExpirationMs;
    private final long scanExpirationMs;
    private final long inviteExpirationMs;

    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.login-expiration-ms}") long loginExpirationMs,
            @Value("${app.jwt.scan-expiration-ms}") long scanExpirationMs,
            @Value("${app.jwt.invite-expiration-ms}") long inviteExpirationMs) {

        // HMAC-SHA256 requires a key of at least 256 bits (32 bytes).
        // We derive the key from the configured secret string.
        this.signingKey       = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.loginExpirationMs = loginExpirationMs;
        this.scanExpirationMs  = scanExpirationMs;
        this.inviteExpirationMs = inviteExpirationMs;
    }

    // ── Token generation ────────────────────────────────────

    /**
     * Creates a long-lived professor login token (default 24 hrs).
     *
     * @param professorId the professor's UUID (stored as the JWT subject)
     * @param email       the professor's email (stored as the "email" claim)
     * @return a signed JWT string
     */
    public String generateLoginToken(UUID professorId, String email) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + loginExpirationMs);

        return Jwts.builder()
                .subject(professorId.toString())
                .claim(CLAIM_TYPE, TYPE_LOGIN)
                .claim(CLAIM_EMAIL, email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Creates a short-lived scan token (default 15 s) embedded in QR code URLs.
     *
     * @param sessionId the QR session's UUID (stored as the JWT subject)
     * @return a signed JWT string
     */
    public String generateScanToken(UUID sessionId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + scanExpirationMs);

        return Jwts.builder()
                .subject(sessionId.toString())
                .claim(CLAIM_TYPE, TYPE_SCAN)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Creates a medium-lived invite token (default 48 hrs) for new professor registration.
     *
     * @return a signed JWT string representing an invite code
     */
    public String generateInviteToken() {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + inviteExpirationMs);

        return Jwts.builder()
                .subject("admin-invite")
                .claim(CLAIM_TYPE, TYPE_INVITE)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // ── Token validation ────────────────────────────────────

    /**
     * Parses and validates a JWT. Returns the token's claims if valid.
     *
     * @param token the compact JWT string
     * @return the parsed {@link Claims}
     * @throws JwtException if the token is malformed, expired, or the signature is invalid
     */
    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // ── Claim extraction helpers ────────────────────────────

    /**
     * Extracts the professor UUID from a LOGIN token.
     *
     * @throws JwtException          if the token is invalid or expired
     * @throws IllegalStateException if the token is not of type LOGIN
     */
    public UUID extractProfessorId(String token) {
        Claims claims = validateToken(token);
        assertTokenType(claims, TYPE_LOGIN);
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Extracts the session UUID from a SCAN token.
     *
     * @throws JwtException          if the token is invalid or expired
     * @throws IllegalStateException if the token is not of type SCAN
     */
    public UUID extractSessionId(String token) {
        Claims claims = validateToken(token);
        assertTokenType(claims, TYPE_SCAN);
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Extracts the email from a LOGIN token.
     *
     * @throws JwtException          if the token is invalid or expired
     * @throws IllegalStateException if the token is not of type LOGIN
     */
    public String extractEmail(String token) {
        Claims claims = validateToken(token);
        assertTokenType(claims, TYPE_LOGIN);
        return claims.get(CLAIM_EMAIL, String.class);
    }

    /**
     * Returns the token type claim ("LOGIN" or "SCAN").
     */
    public String extractTokenType(String token) {
        Claims claims = validateToken(token);
        return claims.get(CLAIM_TYPE, String.class);
    }

    /**
     * Checks whether a token has expired or is otherwise invalid.
     * Returns {@code true} if the token is expired/invalid, {@code false} if still valid.
     * <p>
     * Unlike the other extract methods, this does NOT throw on expired tokens —
     * it catches the expiration exception and returns true.
     * <p>
     * Note: JJWT's {@code parseSignedClaims} already rejects expired tokens by
     * throwing {@code ExpiredJwtException}, so a successful {@code validateToken}
     * call guarantees the token is not expired.
     */
    public boolean isTokenExpired(String token) {
        try {
            validateToken(token);
            return false;
        } catch (JwtException e) {
            // If parsing fails because the token is expired (or any other reason),
            // we consider it "expired" from the caller's perspective.
            return true;
        }
    }

    // ── Convenience query methods ───────────────────────────

    /** Returns true if the token is a valid LOGIN token (not expired, correct type). */
    public boolean isLoginToken(String token) {
        try {
            Claims claims = validateToken(token);
            return TYPE_LOGIN.equals(claims.get(CLAIM_TYPE, String.class));
        } catch (JwtException e) {
            return false;
        }
    }

    /** Returns true if the token is a valid SCAN token (not expired, correct type). */
    public boolean isScanToken(String token) {
        try {
            Claims claims = validateToken(token);
            return TYPE_SCAN.equals(claims.get(CLAIM_TYPE, String.class));
        } catch (JwtException e) {
            return false;
        }
    }

    /** Returns true if the token is a valid INVITE token (not expired, correct type). */
    public boolean isInviteToken(String token) {
        try {
            Claims claims = validateToken(token);
            return TYPE_INVITE.equals(claims.get(CLAIM_TYPE, String.class));
        } catch (JwtException e) {
            return false;
        }
    }

    // ── Internal helpers ────────────────────────────────────

    private void assertTokenType(Claims claims, String expectedType) {
        String actualType = claims.get(CLAIM_TYPE, String.class);
        if (!expectedType.equals(actualType)) {
            throw new IllegalStateException(
                    "Expected token type " + expectedType + " but got " + actualType);
        }
    }
}
