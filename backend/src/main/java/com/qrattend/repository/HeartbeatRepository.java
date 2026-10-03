package com.qrattend.repository;

import com.qrattend.entity.Heartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HeartbeatRepository extends JpaRepository<Heartbeat, UUID> {

    /** All heartbeats for a student in a session, ordered by time (for coverage computation). */
    List<Heartbeat> findBySessionIdAndRollNumberOrderByReceivedAtAsc(UUID sessionId, String rollNumber);

    /** Count heartbeats for a student in a session (quick coverage check). */
    long countBySessionIdAndRollNumber(UUID sessionId, String rollNumber);

    /** Delete all heartbeats for a student in a session (used when rescanning for a fresh chance). */
    void deleteBySessionIdAndRollNumber(UUID sessionId, String rollNumber);
}
