package com.qrattend.repository;

import com.qrattend.entity.EmailOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailOtpRepository extends JpaRepository<EmailOtp, UUID> {

    Optional<EmailOtp> findByEmail(String email);

    /** Number of codes sent to distinct emails since {@code since} — used for the daily sending cap. */
    long countByLastSentAtAfter(Instant since);

    /** Removes codes that have expired (keeps the table from growing). */
    @Modifying
    @Query("DELETE FROM EmailOtp o WHERE o.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
