package com.qrattend.repository;

import com.qrattend.entity.Attendance;
import com.qrattend.entity.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    List<Attendance> findBySessionId(UUID sessionId);

    Optional<Attendance> findBySessionIdAndRollNumber(UUID sessionId, String rollNumber);

    boolean existsBySessionIdAndRollNumber(UUID sessionId, String rollNumber);

    /**
     * Find all attendance records in a specific status for a session.
     * Used by the coverage scheduler to find PENDING records to finalize.
     */
    List<Attendance> findBySessionIdAndStatus(UUID sessionId, AttendanceStatus status);

    /** Count attendance records in a given status for a session. */
    long countBySessionIdAndStatus(UUID sessionId, AttendanceStatus status);

    /**
     * Directly transitions a single attendance record to a given status.
     *
     * <p>Intended for use by the future Manual Override module, which must be able to
     * finalize individual records on a <em>closed</em> session without triggering the
     * coverage scheduler (which only runs for open sessions).</p>
     *
     * <p>Example usage:
     * <pre>{@code
     * attendanceRepository.markAttendanceAsStatus(attendanceId, AttendanceStatus.CONFIRMED);
     * }</pre>
     * </p>
     */
    @Modifying
    @Query("UPDATE Attendance a SET a.status = :status WHERE a.id = :id")
    int markAttendanceAsStatus(@Param("id") UUID id, @Param("status") AttendanceStatus status);
}
