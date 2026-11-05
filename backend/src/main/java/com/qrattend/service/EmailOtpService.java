package com.qrattend.service;

import com.qrattend.dto.auth.OtpResponse;
import com.qrattend.entity.EmailOtp;
import com.qrattend.exception.DuplicateResourceException;
import com.qrattend.exception.EmailNotAllowedException;
import com.qrattend.exception.InvalidOtpException;
import com.qrattend.exception.TooManyRequestsException;
import com.qrattend.mail.EmailSender;
import com.qrattend.repository.EmailOtpRepository;
import com.qrattend.repository.ProfessorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Email ownership verification for professor registration.
 *
 * <p>Flow: {@link #requestOtp} emails a 6-digit code; {@link #verifyAndConsume} checks it
 * when the professor submits the registration form. Only a BCrypt hash of the code is stored.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Domains:</b> the address must be at an allowed domain or one of its subdomains
 *       ({@code iitm.ac.in}, {@code cse.iitm.ac.in}, …) and not at a blocked one
 *       ({@code smail.iitm.ac.in} — student addresses).</li>
 *   <li><b>Expiry:</b> a code is valid for {@code app.otp.ttl-seconds} (10 min).</li>
 *   <li><b>Guessing:</b> after {@code app.otp.max-attempts} (5) wrong guesses the code is dead.</li>
 *   <li><b>Sending limits:</b> one code per {@code app.otp.resend-cooldown-seconds} (60 s) per email,
 *       at most {@code app.otp.max-sends-per-hour} (5) per email per hour, and at most
 *       {@code app.otp.daily-limit} emails in total per 24 h (protects the provider's quota).</li>
 * </ul>
 */
@Service
@Slf4j
public class EmailOtpService {

    private static final Duration SEND_WINDOW = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailOtpRepository otpRepository;
    private final ProfessorRepository professorRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;

    private final List<String> allowedDomains;
    private final List<String> blockedDomains;
    private final Duration ttl;
    private final Duration resendCooldown;
    private final int maxSendsPerHour;
    private final int maxAttempts;
    private final int dailyLimit;

    private Clock clock = Clock.systemUTC();

    public EmailOtpService(
            EmailOtpRepository otpRepository,
            ProfessorRepository professorRepository,
            PasswordEncoder passwordEncoder,
            EmailSender emailSender,
            @Value("${app.registration.allowed-domains:iitm.ac.in}") List<String> allowedDomains,
            @Value("${app.registration.blocked-domains:smail.iitm.ac.in}") List<String> blockedDomains,
            @Value("${app.otp.ttl-seconds:600}") long ttlSeconds,
            @Value("${app.otp.resend-cooldown-seconds:60}") long resendCooldownSeconds,
            @Value("${app.otp.max-sends-per-hour:5}") int maxSendsPerHour,
            @Value("${app.otp.max-attempts:5}") int maxAttempts,
            @Value("${app.otp.daily-limit:250}") int dailyLimit) {
        this.otpRepository = otpRepository;
        this.professorRepository = professorRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.allowedDomains = normaliseDomains(allowedDomains);
        if (this.allowedDomains.isEmpty()) {
            throw new IllegalStateException("app.registration.allowed-domains must list at least one domain");
        }
        this.blockedDomains = normaliseDomains(blockedDomains);
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.resendCooldown = Duration.ofSeconds(resendCooldownSeconds);
        this.maxSendsPerHour = maxSendsPerHour;
        this.maxAttempts = maxAttempts;
        this.dailyLimit = dailyLimit;
    }

    /** Test hook: lets tests move time forward. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ── Email rules ───────────────────────────────────────────

    /** Trims and lower-cases an email so lookups and storage are consistent. */
    public static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Throws unless the (normalised) email is at an allowed domain or subdomain and
     * not at a blocked one.
     */
    public void assertAllowed(String email) {
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? "" : email.substring(at + 1);
        if (matchesAny(domain, blockedDomains)) {
            throw new EmailNotAllowedException(
                    "Student email addresses (@" + blockedDomains.get(0) + ") can't register. Use your faculty email.");
        }
        if (!matchesAny(domain, allowedDomains)) {
            throw new EmailNotAllowedException(
                    "Only @" + allowedDomains.get(0) + " email addresses can register.");
        }
    }

    // ── Send a code ───────────────────────────────────────────

    /**
     * Generates a new code for the email and sends it. Replaces any previous code.
     *
     * @throws EmailNotAllowedException   if the domain isn't allowed
     * @throws DuplicateResourceException if a professor already uses this email
     * @throws TooManyRequestsException   if a sending limit applies
     * @throws com.qrattend.mail.EmailDeliveryException if the provider fails (nothing is saved)
     */
    @Transactional
    public OtpResponse requestOtp(String rawEmail) {
        String email = normalise(rawEmail);
        assertAllowed(email);

        if (professorRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateResourceException("An account with this email already exists. Log in instead.");
        }

        Instant now = clock.instant();
        EmailOtp otp = otpRepository.findByEmail(email).orElse(null);

        if (otp != null) {
            Instant nextAllowed = otp.getLastSentAt().plus(resendCooldown);
            if (now.isBefore(nextAllowed)) {
                long wait = Duration.between(now, nextAllowed).toSeconds() + 1;
                throw new TooManyRequestsException("Please wait " + wait + " seconds before requesting another code.", wait);
            }
            if (!now.isBefore(otp.getWindowStart().plus(SEND_WINDOW))) {
                otp.setWindowStart(now);
                otp.setSendCount(0);
            }
            if (otp.getSendCount() >= maxSendsPerHour) {
                long wait = Duration.between(now, otp.getWindowStart().plus(SEND_WINDOW)).toSeconds() + 1;
                throw new TooManyRequestsException("Too many codes requested for this email. Try again later.", wait);
            }
        }

        if (otpRepository.countByLastSentAtAfter(now.minus(Duration.ofHours(24))) >= dailyLimit) {
            log.warn("OTP daily sending limit ({}) reached", dailyLimit);
            throw new TooManyRequestsException("Too many verification emails have been sent today. Try again later.", 3600);
        }

        if (otp == null) {
            otp = EmailOtp.builder().email(email).windowStart(now).sendCount(0).build();
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        otp.setCodeHash(passwordEncoder.encode(code));
        otp.setExpiresAt(now.plus(ttl));
        otp.setAttempts(0);
        otp.setLastSentAt(now);
        otp.setSendCount(otp.getSendCount() + 1);
        otpRepository.save(otp);

        // Sent inside the transaction: if the provider fails, the new code isn't saved
        // and the cooldown isn't consumed.
        long minutes = ttl.toMinutes();
        emailSender.send(email,
                "Your QR Attendance verification code",
                "Your verification code is " + code + ".\n\n"
                        + "It expires in " + minutes + " minutes. Enter it on the registration page to finish creating your account.\n\n"
                        + "If you didn't request this, you can ignore this email.",
                "<p>Your verification code is</p>"
                        + "<p style=\"font-size:28px;font-weight:700;letter-spacing:6px;font-family:monospace\">" + code + "</p>"
                        + "<p>It expires in " + minutes + " minutes. Enter it on the registration page to finish creating your account.</p>"
                        + "<p style=\"color:#666\">If you didn't request this, you can ignore this email.</p>");

        log.info("Verification code sent to {}", email);
        return new OtpResponse(ttl.toSeconds(), resendCooldown.toSeconds());
    }

    // ── Check a code ──────────────────────────────────────────

    /**
     * Checks the code for the email. On success the code is deleted so it can't be reused.
     * A wrong guess is recorded even though an exception is thrown (hence {@code noRollbackFor}).
     *
     * @throws InvalidOtpException if there is no code, it expired, it's wrong, or too many wrong guesses
     */
    @Transactional(noRollbackFor = InvalidOtpException.class)
    public void verifyAndConsume(String rawEmail, String code) {
        String email = normalise(rawEmail);
        EmailOtp otp = otpRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidOtpException(
                        "No verification code found for this email. Request a new code."));

        if (clock.instant().isAfter(otp.getExpiresAt())) {
            throw new InvalidOtpException("This code has expired. Request a new code.");
        }
        if (otp.getAttempts() >= maxAttempts) {
            throw new InvalidOtpException("Too many incorrect attempts. Request a new code.");
        }
        if (code == null || !passwordEncoder.matches(code.trim(), otp.getCodeHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpRepository.save(otp);
            int left = maxAttempts - otp.getAttempts();
            throw new InvalidOtpException(left > 0
                    ? "Incorrect code. " + left + " attempt" + (left == 1 ? "" : "s") + " left."
                    : "Too many incorrect attempts. Request a new code.");
        }

        otpRepository.delete(otp);
    }

    // ── Cleanup ───────────────────────────────────────────────

    /**
     * Hourly: deletes codes that expired more than an hour ago. Keeping them for an hour
     * past expiry preserves the per-email hourly sending limit.
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    @Transactional
    public void purgeExpired() {
        int removed = otpRepository.deleteExpiredBefore(clock.instant().minus(SEND_WINDOW));
        if (removed > 0) {
            log.info("Purged {} expired verification code(s)", removed);
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private static boolean matchesAny(String domain, List<String> candidates) {
        return candidates.stream().anyMatch(c -> domain.equals(c) || domain.endsWith("." + c));
    }

    private static List<String> normaliseDomains(List<String> domains) {
        return domains.stream()
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .map(d -> d.startsWith("@") ? d.substring(1) : d)
                .filter(d -> !d.isEmpty())
                .toList();
    }
}
