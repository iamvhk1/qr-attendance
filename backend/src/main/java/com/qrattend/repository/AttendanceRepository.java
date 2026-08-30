package com.qrattend.repository;

import com.qrattend.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    List<Attendance> findBySessionId(UUID sessionId);

    Optional<Attendance> findBySessionIdAndRollNumber(UUID sessionId, String rollNumber);

    boolean existsBySessionIdAndRollNumber(UUID sessionId, String rollNumber);

    /** Find all PENDING attendance records for a session (used when session closes to finalize). */
    List<Attendance> findBySessionIdAndStatus(UUID sessionId, String status);

    /** Count present students for a session (only CONFIRMED status). */
    long countBySessionIdAndStatus(UUID sessionId, String status);
}
