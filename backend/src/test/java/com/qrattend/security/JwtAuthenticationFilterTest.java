package com.qrattend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JwtAuthenticationFilter}.
 *
 * <p>These are pure unit tests — no Spring context is loaded.
 * Uses a real {@link JwtUtil} instance (same approach as JwtUtilTest)
 * to avoid Mockito inline-mock limitations on Java 26.</p>
 */
class JwtAuthenticationFilterTest {

    /**
     * Same 64-character Base64 secret used in JwtUtilTest —
     * satisfies HMAC-SHA256's 256-bit minimum key length.
     */
    private static final String TEST_SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdW5pdC10ZXN0cy0yMDI2LW11c3QtYmUtMjU2LWJpdHM=";
    private static final long LOGIN_EXPIRATION_MS = 86_400_000L;
    private static final long SCAN_EXPIRATION_MS = 15_000L;
    private static final long INVITE_EXPIRATION_MS = 172_800_000L;

    private JwtUtil jwtUtil;
    private JwtAuthenticationFilter filter;
    private FilterChain filterChain;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(TEST_SECRET, LOGIN_EXPIRATION_MS, SCAN_EXPIRATION_MS, INVITE_EXPIRATION_MS);
        filter = new JwtAuthenticationFilter(jwtUtil);
        filterChain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── No Authentication ───────────────────────────────────

    @Test
    @DisplayName("No Authorization header → filter chain continues, no authentication set")
    void noAuthorizationHeader_noAuthentication() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Authorization header without 'Bearer ' prefix → no authentication set")
    void malformedAuthorizationHeader_noAuthentication() throws ServletException, IOException {
        request.addHeader("Authorization", "Basic some-credentials");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Empty Bearer value → no authentication set")
    void emptyBearerToken_noAuthentication() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer ");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Expired / Invalid Tokens ────────────────────────────

    @Test
    @DisplayName("Expired token → filter chain continues, no authentication set")
    void expiredToken_noAuthentication() throws ServletException, IOException {
        // Create a JwtUtil with 0ms login expiration → token is immediately expired
        JwtUtil expiredJwtUtil = new JwtUtil(TEST_SECRET, 0L, SCAN_EXPIRATION_MS, INVITE_EXPIRATION_MS);
        String expiredToken = expiredJwtUtil.generateLoginToken(UUID.randomUUID(), "test@iitm.ac.in");

        request.addHeader("Authorization", "Bearer " + expiredToken);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Tampered token → filter chain continues, no authentication set")
    void tamperedToken_noAuthentication() throws ServletException, IOException {
        String validToken = jwtUtil.generateLoginToken(UUID.randomUUID(), "test@iitm.ac.in");
        String tampered = validToken.substring(0, validToken.length() - 5) + "XXXXX";

        request.addHeader("Authorization", "Bearer " + tampered);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Garbage JWT string → filter chain continues, no authentication set")
    void garbageToken_noAuthentication() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer not.a.jwt");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Valid LOGIN Token ───────────────────────────────────

    @Test
    @DisplayName("Valid LOGIN token → SecurityContext contains ROLE_PROFESSOR with professorId as principal")
    void validLoginToken_setsRoleProfessor() throws ServletException, IOException {
        UUID professorId = UUID.randomUUID();
        String loginToken = jwtUtil.generateLoginToken(professorId, "prof@iitm.ac.in");

        request.addHeader("Authorization", "Bearer " + loginToken);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(professorId);
        assertThat(auth.getCredentials()).isNull();
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_PROFESSOR");
    }

    // ── Valid SCAN Token ────────────────────────────────────

    @Test
    @DisplayName("Valid SCAN token → SecurityContext contains ROLE_SCAN with sessionId as principal")
    void validScanToken_setsRoleScan() throws ServletException, IOException {
        UUID sessionId = UUID.randomUUID();
        String scanToken = jwtUtil.generateScanToken(sessionId);

        request.addHeader("Authorization", "Bearer " + scanToken);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(sessionId);
        assertThat(auth.getCredentials()).isNull();
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_SCAN");
    }

    // ── Unknown Token Type ──────────────────────────────────

    @Test
    @DisplayName("Valid INVITE token → no authentication set (filter only handles LOGIN and SCAN)")
    void inviteToken_noAuthentication() throws ServletException, IOException {
        String inviteToken = jwtUtil.generateInviteToken();

        request.addHeader("Authorization", "Bearer " + inviteToken);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Token from different secret ─────────────────────────

    @Test
    @DisplayName("Token signed with a different secret → no authentication set")
    void differentSecretToken_noAuthentication() throws ServletException, IOException {
        JwtUtil otherJwtUtil = new JwtUtil(
                "YW5vdGhlci1zZWNyZXQta2V5LXRoYXQtaXMtZGlmZmVyZW50LWZyb20tdGhlLW9yaWdpbmFsLWtleQ==",
                LOGIN_EXPIRATION_MS, SCAN_EXPIRATION_MS, INVITE_EXPIRATION_MS);
        String foreignToken = otherJwtUtil.generateLoginToken(UUID.randomUUID(), "hacker@evil.com");

        request.addHeader("Authorization", "Bearer " + foreignToken);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
