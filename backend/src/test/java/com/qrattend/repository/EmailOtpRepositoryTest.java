package com.qrattend.repository;

import com.qrattend.entity.EmailOtp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@DisplayName("EmailOtpRepository")
class EmailOtpRepositoryTest {

    @Autowired private EmailOtpRepository repository;
    @Autowired private TestEntityManager entityManager;

    private final Instant now = Instant.parse("2026-11-05T10:00:00Z");

    private EmailOtp otp(String email, Instant sentAt, Instant expiresAt) {
        return EmailOtp.builder()
                .email(email).codeHash("$2a$04$hash")
                .lastSentAt(sentAt).windowStart(sentAt).sendCount(1)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("findByEmail returns the stored row with defaults applied")
    void findByEmail() {
        repository.save(otp("a@iitm.ac.in", now, now.plusSeconds(600)));
        entityManager.flush();
        entityManager.clear();

        EmailOtp found = repository.findByEmail("a@iitm.ac.in").orElseThrow();
        assertThat(found.getAttempts()).isZero();
        assertThat(found.getSendCount()).isEqualTo(1);
        assertThat(repository.findByEmail("b@iitm.ac.in")).isEmpty();
    }

    @Test
    @DisplayName("Only one row per email (unique constraint)")
    void uniqueEmail() {
        repository.saveAndFlush(otp("dup@iitm.ac.in", now, now.plusSeconds(600)));

        assertThatThrownBy(() -> repository.saveAndFlush(otp("dup@iitm.ac.in", now, now.plusSeconds(600))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("countByLastSentAtAfter counts only codes sent after the cutoff")
    void countSince() {
        repository.save(otp("old@iitm.ac.in", now.minus(Duration.ofHours(30)), now));
        repository.save(otp("new1@iitm.ac.in", now.minus(Duration.ofHours(2)), now));
        repository.save(otp("new2@iitm.ac.in", now, now));
        entityManager.flush();

        assertThat(repository.countByLastSentAtAfter(now.minus(Duration.ofHours(24)))).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteExpiredBefore removes only rows that expired before the cutoff")
    void deleteExpired() {
        repository.save(otp("expired@iitm.ac.in", now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(2))));
        repository.save(otp("recent@iitm.ac.in", now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(20))));
        repository.save(otp("valid@iitm.ac.in", now, now.plusSeconds(600)));
        entityManager.flush();

        int removed = repository.deleteExpiredBefore(now.minus(Duration.ofHours(1)));
        entityManager.clear();

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findByEmail("expired@iitm.ac.in")).isEmpty();
        assertThat(repository.findByEmail("recent@iitm.ac.in")).isPresent();
        assertThat(repository.findByEmail("valid@iitm.ac.in")).isPresent();
    }
}
