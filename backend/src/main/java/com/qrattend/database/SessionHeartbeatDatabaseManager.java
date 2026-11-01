package com.qrattend.database;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages isolated, per-session databases for high-frequency student presence heartbeats.
 *
 * <p>Architectural advantages:</p>
 * <ul>
 *   <li>The primary relational DB is completely insulated from high-frequency heartbeat write bursts.</li>
 *   <li>Within each session database, {@code session_id} is eliminated from the table and composite index;
 *       it is indexed strictly by {@code roll_number}.</li>
 *   <li>When a session closes and coverage is computed, the entire session database can be dropped/deleted
 *       cleanly in O(1) time without generating WAL bloat or row-by-row table locking on the primary DB.</li>
 * </ul>
 */
@Component
@Slf4j
public class SessionHeartbeatDatabaseManager {

    @Value("${app.session.db-storage-path:./data/sessions}")
    private String storagePath;

    /** Map of active DataSource / JdbcTemplate instances per session UUID. */
    private final Map<UUID, JdbcTemplate> sessionTemplates = new ConcurrentHashMap<>();

    /**
     * Cleans up any leftover orphan session database files on application startup
     * (e.g. if the server previously crashed or was killed while sessions were active).
     */
    @PostConstruct
    public void cleanupOrphanSessionDatabases() {
        if (storagePath == null || storagePath.equalsIgnoreCase("mem") || storagePath.startsWith("mem:")) {
            return;
        }
        try {
            File dir = new File(storagePath);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.startsWith("session_") && (name.endsWith(".db") || name.endsWith(".trace.db")));
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        boolean deleted = file.delete();
                        if (deleted) {
                            log.info("Cleaned up orphan session database file on startup: {}", file.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not clean up orphan session database files on startup: {}", e.getMessage());
        }
    }

    /**
     * Initializes an isolated database for a session if not already created.
     *
     * @param sessionId the UUID of the session
     * @return the initialized {@link JdbcTemplate}
     */
    public synchronized JdbcTemplate initSessionDatabase(UUID sessionId) {
        if (sessionTemplates.containsKey(sessionId)) {
            return sessionTemplates.get(sessionId);
        }

        String dbUrl;
        if (storagePath != null && (storagePath.equalsIgnoreCase("mem") || storagePath.startsWith("mem:"))) {
            dbUrl = String.format("jdbc:h2:mem:session_%s;DB_CLOSE_DELAY=-1", sessionId.toString().replace("-", ""));
        } else {
            File dir = new File(storagePath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String cleanId = sessionId.toString().replace("-", "");
            dbUrl = String.format("jdbc:h2:%s/session_%s;DB_CLOSE_DELAY=-1", storagePath.replace("\\", "/"), cleanId);
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(dbUrl);
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        JdbcTemplate template = new JdbcTemplate(dataSource);

        // Session-isolated table: NO session_id column. Indexed strictly by roll_number!
        template.execute("""
            CREATE TABLE IF NOT EXISTS heartbeats (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                roll_number VARCHAR(20) NOT NULL,
                received_at TIMESTAMP NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_heartbeats_roll ON heartbeats(roll_number);
        """);

        sessionTemplates.put(sessionId, template);
        log.info("Initialized isolated database for session {} at {}", sessionId, dbUrl);
        return template;
    }

    /**
     * Gets or creates the {@link JdbcTemplate} for the specified session.
     */
    public JdbcTemplate getTemplate(UUID sessionId) {
        JdbcTemplate template = sessionTemplates.get(sessionId);
        if (template == null) {
            return initSessionDatabase(sessionId);
        }
        return template;
    }

    /**
     * Records a single student heartbeat ping in the session's isolated database.
     *
     * @param sessionId  the session UUID
     * @param rollNumber the student's roll number
     */
    public void recordHeartbeat(UUID sessionId, String rollNumber) {
        JdbcTemplate template = getTemplate(sessionId);
        template.update(
            "INSERT INTO heartbeats (roll_number, received_at) VALUES (?, ?)",
            rollNumber, Timestamp.from(Instant.now())
        );
        log.debug("Heartbeat ping stored in session DB for student {} in session {}", rollNumber, sessionId);
    }

    /**
     * Counts the total number of heartbeat pings received from a student in the session DB.
     *
     * @param sessionId  the session UUID
     * @param rollNumber the student's roll number
     * @return the count of heartbeats
     */
    public long countHeartbeats(UUID sessionId, String rollNumber) {
        JdbcTemplate template = getTemplate(sessionId);
        Long count = template.queryForObject(
            "SELECT COUNT(*) FROM heartbeats WHERE roll_number = ?",
            Long.class,
            rollNumber
        );
        return count != null ? count : 0L;
    }

    /**
     * Deletes all heartbeats for a student within the session DB (used when giving a fresh rescan chance).
     *
     * @param sessionId  the session UUID
     * @param rollNumber the student's roll number
     */
    public void deleteHeartbeats(UUID sessionId, String rollNumber) {
        JdbcTemplate template = getTemplate(sessionId);
        template.update("DELETE FROM heartbeats WHERE roll_number = ?", rollNumber);
        log.debug("Cleared previous heartbeats in session DB for student {} in session {}", rollNumber, sessionId);
    }

    /**
     * Closes and drops the isolated session database, removing its storage files/memory.
     *
     * @param sessionId the session UUID
     */
    public void closeAndDropSessionDatabase(UUID sessionId) {
        JdbcTemplate template = sessionTemplates.remove(sessionId);
        if (template != null) {
            try {
                template.execute("DROP ALL OBJECTS DELETE FILES");
                log.info("Cleaned up and dropped isolated database for session {}", sessionId);
            } catch (Exception e) {
                log.warn("Error dropping isolated database for session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Checks if a session database is actively open.
     */
    public boolean hasSessionDatabase(UUID sessionId) {
        return sessionTemplates.containsKey(sessionId);
    }
}
