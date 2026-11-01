package com.qrattend.repository;

import com.qrattend.entity.QrSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QrSessionRepository extends JpaRepository<QrSession, UUID> {

    List<QrSession> findByCourseId(UUID courseId);

    List<QrSession> findByCourseIdOrderByCreatedAtDesc(UUID courseId);

    List<QrSession> findByExpiresAtBeforeAndClosedAtIsNull(java.time.Instant instant);

    /**
     * Counts currently active/live sessions for a given professor.
     * A session is active if it has not been closed and its expiration timestamp is in the future.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(s) FROM QrSession s WHERE s.professor.id = :professorId AND s.closedAt IS NULL AND s.expiresAt > :now"
    )
    long countActiveSessionsByProfessorId(
        @org.springframework.data.repository.query.Param("professorId") UUID professorId,
        @org.springframework.data.repository.query.Param("now") java.time.Instant now
    );
}
