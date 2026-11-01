package com.qrattend.repository;

import com.qrattend.database.SessionHeartbeatDatabaseManager;
import com.qrattend.entity.Heartbeat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository interface and manager for heartbeats stored in isolated per-session databases.
 *
 * <p>Delegates storage, counting, and database lifecycle operations to
 * {@link SessionHeartbeatDatabaseManager}.</p>
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class HeartbeatRepository {

    private final SessionHeartbeatDatabaseManager sessionDatabaseManager;

    /**
     * Initializes a fresh, isolated database for a session.
     */
    public void initSessionDatabase(UUID sessionId) {
        sessionDatabaseManager.initSessionDatabase(sessionId);
    }

    /**
     * Records a single student heartbeat ping into the session's isolated database.
     */
    public void save(UUID sessionId, String rollNumber) {
        sessionDatabaseManager.recordHeartbeat(sessionId, rollNumber);
    }

    /**
     * Records a heartbeat using the Heartbeat model and session ID.
     */
    public void save(Heartbeat heartbeat, UUID sessionId) {
        sessionDatabaseManager.recordHeartbeat(sessionId, heartbeat.getRollNumber());
    }

    /**
     * Count heartbeats for a student in a session.
     */
    public long countBySessionIdAndRollNumber(UUID sessionId, String rollNumber) {
        return sessionDatabaseManager.countHeartbeats(sessionId, rollNumber);
    }

    /**
     * Delete all heartbeats for a student in a session (used when rescanning for a fresh chance).
     */
    public void deleteBySessionIdAndRollNumber(UUID sessionId, String rollNumber) {
        sessionDatabaseManager.deleteHeartbeats(sessionId, rollNumber);
    }

    /**
     * Closes and drops the isolated database for an ended session.
     */
    public void closeSessionDatabase(UUID sessionId) {
        sessionDatabaseManager.closeAndDropSessionDatabase(sessionId);
    }

    /**
     * Accessor for the underlying session database manager.
     */
    public SessionHeartbeatDatabaseManager getDatabaseManager() {
        return sessionDatabaseManager;
    }
}
