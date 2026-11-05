package com.qrattend.service;

import com.qrattend.dto.auth.OtpResponse;
import com.qrattend.entity.EmailOtp;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.EmailNotAllowedException;
import com.qrattend.exception.InvalidOtpException;
import com.qrattend.exception.TooManyRequestsException;
import com.qrattend.mail.EmailDeliveryException;
import com.qrattend.mail.EmailSender;
import com.qrattend.repository.EmailOtpRepository;
import com.qrattend.repository.ProfessorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailOtpService}: domain rules, code generation and hashing,
 * sending limits, verification, and cleanup. Uses real BCrypt and a controllable clock;
 * the repository is a small in-memory stand-in backed by a Mockito mock.
 */
@DisplayName("EmailOtpService")
class EmailOtpServiceTest {

    private EmailOtpRepository otpRepository;
    private ProfessorRepository professorRepository;
    private final List<String[]> sent = new ArrayList<>();   // {to, subject, text, html}
    private EmailSender sender;
    private MutableClock clock;
    private EmailOtpService service;

    /** The single stored row (the service keeps at most one per email; tests use one email). */
    private EmailOtp stored;

    private static final String EMAIL = "prof@cse.iitm.ac.in";

    @BeforeEach
    void setUp() {
        otpRepository = mock(EmailOtpRepository.class);
        professorRepository = mock(ProfessorRepository.class);
        sender = (to, subject, text, html) -> sent.add(new String[]{to, subject, text, html});
        clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
        stored = null;

        when(otpRepository.findByEmail(any())).thenAnswer(inv ->
                stored != null && stored.getEmail().equals(inv.getArgument(0)) ? Optional.of(stored) : Optional.empty());
        when(otpRepository.save(any(EmailOtp.class))).thenAnswer(inv -> stored = inv.getArgument(0));
        doAnswer(inv -> { stored = null; return null; }).when(otpRepository).delete(any(EmailOtp.class));
        when(otpRepository.countByLastSentAtAfter(any())).thenReturn(0L);
        when(professorRepository.existsByEmailIgnoreCase(any())).thenReturn(false);

        service = newService(sender, 250);
    }

    private EmailOtpService newService(EmailSender emailSender, int dailyLimit) {
        EmailOtpService s = new EmailOtpService(otpRepository, professorRepository, new BCryptPasswordEncoder(4),
                emailSender, List.of("iitm.ac.in"), List.of("smail.iitm.ac.in"),
                600, 60, 5, 5, dailyLimit);
        s.setClock(clock);
        return s;
    }

    /** Extracts the 6-digit code from the last email sent. */
    private String lastCode() {
        assertThat(sent).isNotEmpty();
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(sent.get(sent.size() - 1)[2]);
        assertThat(m.find()).as("email body contains a 6-digit code").isTrue();
        return m.group(1);
    }

    // ══════════════════════════════════════════════════════════════
    //  Domain rules
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Domain rules")
    class DomainRules {

        @ParameterizedTest(name = "allowed: {0}")
        @ValueSource(strings = {"prof@iitm.ac.in", "prof@cse.iitm.ac.in", "prof@ee.iitm.ac.in", "prof@ee.dept.iitm.ac.in"})
        void allowed(String email) {
            assertThatCode(() -> service.assertAllowed(email)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "student address rejected: {0}")
        @ValueSource(strings = {"cs24b001@smail.iitm.ac.in", "x@sub.smail.iitm.ac.in"})
        void studentDomainRejected(String email) {
            assertThatThrownBy(() -> service.assertAllowed(email))
                    .isInstanceOf(EmailNotAllowedException.class)
                    .hasMessageContaining("Student");
        }

        @ParameterizedTest(name = "other domain rejected: {0}")
        @ValueSource(strings = {
                "someone@gmail.com",
                "someone@notiitm.ac.in",           // suffix without a dot boundary
                "someone@iitm.ac.in.evil.com",     // allowed domain as a prefix
                "someone@iitm.ac",                 // truncated
                "no-at-sign",
                ""})
        void otherDomainsRejected(String email) {
            assertThatThrownBy(() -> service.assertAllowed(email))
                    .isInstanceOf(EmailNotAllowedException.class)
                    .hasMessageContaining("iitm.ac.in");
        }

        @Test
        @DisplayName("normalise trims and lower-cases")
        void normalise() {
            assertThat(EmailOtpService.normalise("  Prof@CSE.IITM.ac.in ")).isEqualTo("prof@cse.iitm.ac.in");
            assertThat(EmailOtpService.normalise(null)).isEmpty();
        }

        @Test
        @DisplayName("requestOtp normalises before checking, so upper-case student addresses are still rejected")
        void upperCaseStudentRejected() {
            assertThatThrownBy(() -> service.requestOtp("CS24B001@SMAIL.IITM.AC.IN"))
                    .isInstanceOf(EmailNotAllowedException.class);
            assertThat(sent).isEmpty();
        }

        @Test
        @DisplayName("an empty allowed-domain list is a configuration error")
        void emptyAllowedListFailsFast() {
            assertThatThrownBy(() -> new EmailOtpService(otpRepository, professorRepository,
                    new BCryptPasswordEncoder(4), sender, List.of(" "), List.of(), 600, 60, 5, 5, 250))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Sending a code
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("requestOtp")
    class RequestOtp {

        @Test
        @DisplayName("Sends a 6-digit code and stores only its hash")
        void sendsCodeAndStoresHash() {
            OtpResponse response = service.requestOtp(EMAIL);

            assertThat(response.expiresInSeconds()).isEqualTo(600);
            assertThat(response.resendAfterSeconds()).isEqualTo(60);

            assertThat(sent).hasSize(1);
            assertThat(sent.get(0)[0]).isEqualTo(EMAIL);
            assertThat(sent.get(0)[1]).containsIgnoringCase("verification code");
            String code = lastCode();
            assertThat(sent.get(0)[3]).contains(code);   // HTML body too

            assertThat(stored.getEmail()).isEqualTo(EMAIL);
            assertThat(stored.getCodeHash()).isNotEqualTo(code).startsWith("$2");
            assertThat(new BCryptPasswordEncoder().matches(code, stored.getCodeHash())).isTrue();
            assertThat(stored.getExpiresAt()).isEqualTo(clock.instant().plusSeconds(600));
            assertThat(stored.getAttempts()).isZero();
            assertThat(stored.getSendCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Department addresses like name@ee.iitm.ac.in get a code (any case)")
        void departmentAddressGetsCode() {
            service.requestOtp("Student.Advisor@EE.IITM.ac.in");
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0)[0]).isEqualTo("student.advisor@ee.iitm.ac.in");
        }

        @Test
        @DisplayName("Stores the normalised email")
        void storesNormalisedEmail() {
            service.requestOtp("  Prof@CSE.IITM.ac.in ");
            assertThat(stored.getEmail()).isEqualTo(EMAIL);
            assertThat(sent.get(0)[0]).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("Codes are random (not repeated across requests)")
        void codesVary() {
            java.util.Set<String> codes = new java.util.HashSet<>();
            for (int i = 0; i < 5; i++) {
                stored = null;
                service.requestOtp(EMAIL);
                codes.add(lastCode());
            }
            assertThat(codes).hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("Already-registered email → 409, nothing sent or stored")
        void alreadyRegistered() {
            when(professorRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> service.requestOtp(EMAIL))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("Log in");
            assertThat(sent).isEmpty();
            assertThat(stored).isNull();
        }

        @Test
        @DisplayName("Resend within the 60 s cooldown → 429 with the remaining wait; allowed after")
        void resendCooldown() {
            service.requestOtp(EMAIL);
            clock.advance(Duration.ofSeconds(20));

            assertThatThrownBy(() -> service.requestOtp(EMAIL))
                    .isInstanceOf(TooManyRequestsException.class)
                    .satisfies(e -> assertThat(((TooManyRequestsException) e).getRetryAfterSeconds()).isBetween(40L, 41L));
            assertThat(sent).hasSize(1);

            clock.advance(Duration.ofSeconds(41));
            service.requestOtp(EMAIL);
            assertThat(sent).hasSize(2);
        }

        @Test
        @DisplayName("A new code replaces the old one and resets the wrong-guess counter")
        void newCodeReplacesOld() {
            service.requestOtp(EMAIL);
            String first = lastCode();
            stored.setAttempts(3);

            clock.advance(Duration.ofSeconds(61));
            service.requestOtp(EMAIL);
            String second = lastCode();

            assertThat(stored.getAttempts()).isZero();
            if (!first.equals(second)) {
                assertThat(new BCryptPasswordEncoder().matches(first, stored.getCodeHash())).isFalse();
            }
            assertThat(new BCryptPasswordEncoder().matches(second, stored.getCodeHash())).isTrue();
        }

        @Test
        @DisplayName("At most 5 codes per email per hour; the window resets after an hour")
        void hourlyLimit() {
            for (int i = 0; i < 5; i++) {
                service.requestOtp(EMAIL);
                clock.advance(Duration.ofSeconds(61));
            }
            assertThatThrownBy(() -> service.requestOtp(EMAIL))
                    .isInstanceOf(TooManyRequestsException.class)
                    .hasMessageContaining("Too many codes");
            assertThat(sent).hasSize(5);

            clock.advance(Duration.ofHours(1));
            service.requestOtp(EMAIL);
            assertThat(sent).hasSize(6);
            assertThat(stored.getSendCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Daily cap across all addresses → 429, nothing sent")
        void dailyCap() {
            when(otpRepository.countByLastSentAtAfter(any())).thenReturn(250L);

            assertThatThrownBy(() -> service.requestOtp(EMAIL))
                    .isInstanceOf(TooManyRequestsException.class)
                    .hasMessageContaining("today");
            assertThat(sent).isEmpty();
            verify(otpRepository).countByLastSentAtAfter(clock.instant().minus(Duration.ofHours(24)));
        }

        @Test
        @DisplayName("Provider failure propagates (the transaction then discards the new code)")
        void providerFailurePropagates() {
            EmailOtpService failing = newService((to, s, t, h) -> {
                throw new EmailDeliveryException("down");
            }, 250);

            assertThatThrownBy(() -> failing.requestOtp(EMAIL)).isInstanceOf(EmailDeliveryException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Checking a code
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("verifyAndConsume")
    class Verify {

        @Test
        @DisplayName("Correct code → row deleted so it can't be reused")
        void correctCodeConsumed() {
            service.requestOtp(EMAIL);
            String code = lastCode();

            service.verifyAndConsume(EMAIL, code);

            assertThat(stored).isNull();
            assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, code))
                    .isInstanceOf(InvalidOtpException.class)
                    .hasMessageContaining("No verification code");
        }

        @Test
        @DisplayName("Email case and whitespace around the code don't matter")
        void normalisesInputs() {
            service.requestOtp(EMAIL);
            service.verifyAndConsume("PROF@cse.IITM.ac.in", " " + lastCode() + " ");
            assertThat(stored).isNull();
        }

        @Test
        @DisplayName("Wrong code → attempt recorded and remaining attempts reported")
        void wrongCodeCounts() {
            service.requestOtp(EMAIL);
            String wrong = lastCode().equals("000000") ? "111111" : "000000";

            assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, wrong))
                    .isInstanceOf(InvalidOtpException.class)
                    .hasMessage("Incorrect code. 4 attempts left.");
            assertThat(stored.getAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("After 5 wrong guesses even the right code is refused")
        void lockedAfterMaxAttempts() {
            service.requestOtp(EMAIL);
            String code = lastCode();
            String wrong = code.equals("000000") ? "111111" : "000000";

            for (int i = 1; i <= 4; i++) {
                int left = 5 - i;
                assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, wrong))
                        .hasMessageContaining(left + " attempt");
            }
            assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, wrong))
                    .hasMessageContaining("Too many incorrect attempts");
            assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, code))
                    .isInstanceOf(InvalidOtpException.class)
                    .hasMessageContaining("Too many incorrect attempts");
            assertThat(stored).isNotNull();
        }

        @Test
        @DisplayName("Expired code (after 10 minutes) is refused")
        void expired() {
            service.requestOtp(EMAIL);
            String code = lastCode();
            clock.advance(Duration.ofMinutes(10).plusSeconds(1));

            assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, code))
                    .isInstanceOf(InvalidOtpException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("Still valid just before expiry")
        void validJustBeforeExpiry() {
            service.requestOtp(EMAIL);
            String code = lastCode();
            clock.advance(Duration.ofMinutes(10));

            assertThatCode(() -> service.verifyAndConsume(EMAIL, code)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("No code requested → refused")
        void noCode() {
            assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, "123456"))
                    .isInstanceOf(InvalidOtpException.class)
                    .hasMessageContaining("Request a new code");
        }

        @Test
        @DisplayName("A code sent to one email doesn't work for another")
        void codeIsBoundToEmail() {
            service.requestOtp(EMAIL);
            String code = lastCode();

            assertThatThrownBy(() -> service.verifyAndConsume("other@iitm.ac.in", code))
                    .isInstanceOf(InvalidOtpException.class);
            assertThat(stored).isNotNull();
        }

        @Test
        @DisplayName("Null code counts as a wrong guess")
        void nullCode() {
            service.requestOtp(EMAIL);
            assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, null)).isInstanceOf(InvalidOtpException.class);
            assertThat(stored.getAttempts()).isEqualTo(1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Cleanup
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("purgeExpired deletes codes that expired more than an hour ago")
    void purgeExpired() {
        when(otpRepository.deleteExpiredBefore(any())).thenReturn(3);

        service.purgeExpired();

        verify(otpRepository).deleteExpiredBefore(clock.instant().minus(Duration.ofHours(1)));
    }

    // ── Test clock ───────────────────────────────────────────────

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
