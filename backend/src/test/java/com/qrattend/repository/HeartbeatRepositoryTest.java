package com.qrattend.repository;

import com.qrattend.database.SessionHeartbeatDatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatRepositoryTest {

    private SessionHeartbeatDatabaseManager databaseManager;
    private HeartbeatRepository heartbeatRepository;

    private UUID session1Id;
    private UUID session2Id;

    @BeforeEach
    void setUp() {
        databaseManager = new SessionHeartbeatDatabaseManager();
        // Use in-memory H2 databases for tests
        ReflectionTestUtils.setField(databaseManager, "storagePath", "mem");

        heartbeatRepository = new HeartbeatRepository(databaseManager);

        session1Id = UUID.randomUUID();
        session2Id = UUID.randomUUID();

        heartbeatRepository.initSessionDatabase(session1Id);
        heartbeatRepository.initSessionDatabase(session2Id);
    }

    @AfterEach
    void tearDown() {
        heartbeatRepository.closeSessionDatabase(session1Id);
        heartbeatRepository.closeSessionDatabase(session2Id);
    }

    // ── CRUD & Operations ───────────────────────────────────

    @Test
    @DisplayName("Save a heartbeat in session DB and verify count increment")
    void saveHeartbeat() {
        heartbeatRepository.save(session1Id, "CS24B001");

        long count = heartbeatRepository.countBySessionIdAndRollNumber(session1Id, "CS24B001");
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("countBySessionIdAndRollNumber returns accurate count for multiple heartbeats")
    void countHeartbeats() {
        heartbeatRepository.save(session1Id, "CS24B001");
        heartbeatRepository.save(session1Id, "CS24B001");
        heartbeatRepository.save(session1Id, "CS24B001");

        long count = heartbeatRepository.countBySessionIdAndRollNumber(session1Id, "CS24B001");
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("countBySessionIdAndRollNumber returns 0 for a student with no heartbeats")
    void countHeartbeatsReturnsZero() {
        long count = heartbeatRepository.countBySessionIdAndRollNumber(session1Id, "CS24B999");
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("Student isolation: Student A's count does not affect Student B's count")
    void studentCountsAreIsolated() {
        heartbeatRepository.save(session1Id, "CS24B001");
        heartbeatRepository.save(session1Id, "CS24B001");
        heartbeatRepository.save(session1Id, "CS24B002");

        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session1Id, "CS24B001")).isEqualTo(2);
        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session1Id, "CS24B002")).isEqualTo(1);
    }

    @Test
    @DisplayName("Session DB isolation: Session 1's heartbeats do NOT bleed into Session 2's database")
    void sessionDatabasesAreIsolated() {
        heartbeatRepository.save(session1Id, "CS24B001");
        heartbeatRepository.save(session1Id, "CS24B001");

        // Session 2 should have 0 heartbeats for that roll number
        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session2Id, "CS24B001")).isEqualTo(0);

        heartbeatRepository.save(session2Id, "CS24B001");
        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session2Id, "CS24B001")).isEqualTo(1);
        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session1Id, "CS24B001")).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteBySessionIdAndRollNumber clears heartbeats for a student upon rescan")
    void deleteHeartbeatsForStudent() {
        heartbeatRepository.save(session1Id, "CS24B001");
        heartbeatRepository.save(session1Id, "CS24B001");
        heartbeatRepository.save(session1Id, "CS24B002");

        heartbeatRepository.deleteBySessionIdAndRollNumber(session1Id, "CS24B001");

        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session1Id, "CS24B001")).isEqualTo(0);
        assertThat(heartbeatRepository.countBySessionIdAndRollNumber(session1Id, "CS24B002")).isEqualTo(1);
    }

    @Test
    @DisplayName("closeSessionDatabase drops the database cleanly")
    void closeSessionDatabaseDropsCleanly() {
        UUID tempSession = UUID.randomUUID();
        heartbeatRepository.initSessionDatabase(tempSession);
        heartbeatRepository.save(tempSession, "CS24B001");

        assertThat(databaseManager.hasSessionDatabase(tempSession)).isTrue();
        heartbeatRepository.closeSessionDatabase(tempSession);
        assertThat(databaseManager.hasSessionDatabase(tempSession)).isFalse();
    }
}
