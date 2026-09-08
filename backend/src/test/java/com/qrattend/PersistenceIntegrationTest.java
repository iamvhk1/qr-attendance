package com.qrattend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrattend.entity.*;
import com.qrattend.repository.*;
import com.qrattend.security.JwtUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Explicit persistence tests — verify that data written in one transaction
 * is correctly readable in a separate, independent transaction.
 *
 * <p>These tests do NOT use {@code @Transactional} rollback because the
 * entire point is to confirm that committed data actually survives across
 * transaction boundaries — the exact property a file-based H2 DB must
 * guarantee before you switch to Postgres.</p>
 *
 * <p><b>Database:</b> Uses a real file-based H2 database
 * ({@code ./data/test-persistence-db.mv.db}) with {@code ddl-auto=update}.
 * This is intentionally different from the in-memory H2 used by all other
 * test classes — here we need durability, not isolation.</p>
 *
 * <p>Tests run in strict {@code @Order} sequence. State is passed between
 * tests via static fields (intentional — simulates a real user session
 * where each HTTP request is a separate transaction).</p>
 *
 * <p>Test groups:</p>
 * <ul>
 *   <li>Orders 1–3   → Professor persistence</li>
 *   <li>Orders 10–12 → Course persistence</li>
 *   <li>Orders 20–24 → Student persistence</li>
 *   <li>Orders 30–33 → Roster sync (Excel = source of truth)</li>
 *   <li>Orders 40–44 → QR Session persistence</li>
 *   <li>Orders 50–50 → Referential integrity / cascade delete</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // ── File-based H2: data is written to disk, not just in memory ──
        "spring.datasource.url=jdbc:h2:file:./data/test-persistence-db;AUTO_SERVER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // update: preserves the schema across test runs (does not drop on shutdown)
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@DisplayName("Persistence Tests — Data Survives Across Transactions")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersistenceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ProfessorRepository professorRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private QrSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // Unique email — avoids collisions with SecurityIntegrationTest or Phase1IntegrationTest
    private static final String EMAIL    = "persist.prof@test.com";
    private static final String PASSWORD = "Persist1234!";

    // Shared state across ordered tests — set in early tests, read in later ones
    private static UUID  persistedProfId;
    private static UUID  persistedCourseId;
    private static UUID  persistedStudentId;
    private static UUID  persistedSessionId;
    private static String cachedJwt;          // refreshed once per login call

    @AfterAll
    static void globalCleanup(@Autowired ProfessorRepository professorRepository,
                               @Autowired CourseRepository courseRepository) {
        professorRepository.findByEmail(EMAIL).ifPresent(prof -> {
            courseRepository.findAll().stream()
                    .filter(c -> c.getProfessor().getId().equals(prof.getId()))
                    .forEach(courseRepository::delete);
            professorRepository.delete(prof);
        });
    }

    // ── Shared helpers ───────────────────────────────────────────

    /**
     * Returns a cached login JWT. Self-bootstrapping: if the professor doesn't exist yet
     * (e.g. when running a subset of tests in isolation), it registers them first.
     * This ensures every test can call jwt() safely regardless of execution order.
     */
    private String jwt() throws Exception {
        if (cachedJwt != null) return cachedJwt;

        // Try to log in first (professor may already exist from a prior test)
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();

        if (loginResult.getResponse().getStatus() == 401) {
            // Professor not registered yet — bootstrap: get invite and register
            MvcResult inviteResult = mockMvc.perform(post("/api/admin/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminSecret\":\"changeme-admin-secret-2026\"}"))
                    .andExpect(status().isOk()).andReturn();
            String invite = objectMapper.readTree(
                    inviteResult.getResponse().getContentAsString()).get("inviteCode").asText();

            MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"inviteCode\":\"" + invite + "\",\"email\":\"" + EMAIL +
                                    "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Dr. Persist\"}"))
                    .andExpect(status().isCreated()).andReturn();

            persistedProfId = UUID.fromString(
                    objectMapper.readTree(regResult.getResponse().getContentAsString())
                            .get("professorId").asText());

            // Retry login
            loginResult = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
        } else {
            // Professor existed — extract their ID from the login response
            JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
            persistedProfId = UUID.fromString(loginBody.get("professorId").asText());
        }

        cachedJwt = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("token").asText();
        return cachedJwt;
    }

    private byte[] buildExcel(String[][] rows) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Students");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("rollNumber");
        header.createCell(1).setCellValue("fullName");
        for (int i = 0; i < rows.length; i++) {
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(rows[i][0]);
            row.createCell(1).setCellValue(rows[i][1]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private MockMultipartFile excelFile(String[][] rows) throws Exception {
        return new MockMultipartFile("file", "students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                buildExcel(rows));
    }

    // ═══════════════════════════════════════════════════════════════
    //  PART 1: PROFESSOR PERSISTENCE
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(1)
    @DisplayName("P1-a: Register professor via HTTP → committed to DB in new transaction")
    void prof_registeredAndPersisted() throws Exception {
        // Get invite code
        MvcResult inviteResult = mockMvc.perform(post("/api/admin/invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminSecret\":\"changeme-admin-secret-2026\"}"))
                .andExpect(status().isOk()).andReturn();
        String invite = objectMapper.readTree(
                inviteResult.getResponse().getContentAsString()).get("inviteCode").asText();

        // Register
        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"," +
                                "\"email\":\"" + EMAIL + "\"," +
                                "\"password\":\"" + PASSWORD + "\"," +
                                "\"fullName\":\"Dr. Persist\"}"))
                .andExpect(status().isCreated()).andReturn();

        persistedProfId = UUID.fromString(
                objectMapper.readTree(regResult.getResponse().getContentAsString())
                        .get("professorId").asText());

        // Independent repository call (new transaction) — data must be there
        Optional<Professor> fromDb = professorRepository.findByEmail(EMAIL);
        assertThat(fromDb).isPresent();
        assertThat(fromDb.get().getId()).isEqualTo(persistedProfId);
        assertThat(fromDb.get().getFullName()).isEqualTo("Dr. Persist");
        assertThat(fromDb.get().getPasswordHash()).startsWith("$2a$"); // bcrypt
        assertThat(fromDb.get().getPasswordHash()).doesNotContain(PASSWORD);
        assertThat(fromDb.get().getCreatedAt()).isNotNull();
    }

    @Test @Order(2)
    @DisplayName("P1-b: Login works after registration transaction committed (bcrypt hash is valid)")
    void prof_loginAfterRegistration() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professorId").value(persistedProfId.toString()))
                .andReturn();

        cachedJwt = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();

        assertThat(cachedJwt).isNotBlank();
        assertThat(cachedJwt.split("\\.")).hasSize(3);
    }

    @Test @Order(3)
    @DisplayName("P1-c: Professor count in DB is at least 1 after registration")
    void prof_countAtLeastOne() {
        assertThat(professorRepository.count()).isGreaterThanOrEqualTo(1);
        assertThat(professorRepository.findByEmail(EMAIL)).isPresent();
    }

    // ═══════════════════════════════════════════════════════════════
    //  PART 2: COURSE PERSISTENCE
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(10)
    @DisplayName("P2-a: Course created via endpoint is readable from repository in new transaction")
    void course_persistedAcrossTransactions() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/courses")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Operating Systems\"," +
                                "\"code\":\"CS5013\"," +
                                "\"semester\":\"Sem 5 - 2026\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        persistedCourseId = UUID.fromString(body.get("id").asText());

        // New transaction: read directly from repo
        Optional<Course> fromDb = courseRepository.findById(persistedCourseId);
        assertThat(fromDb).isPresent();
        assertThat(fromDb.get().getName()).isEqualTo("Operating Systems");
        assertThat(fromDb.get().getCode()).isEqualTo("CS5013");
        assertThat(fromDb.get().getSemester()).isEqualTo("Sem 5 - 2026");
        assertThat(fromDb.get().getProfessor().getId()).isEqualTo(persistedProfId);
    }

    @Test @Order(11)
    @DisplayName("P2-b: GET /api/courses returns the committed course in next request")
    void course_visibleViaHttpAfterCommit() throws Exception {
        mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + persistedCourseId + "')]").exists());
    }

    @Test @Order(12)
    @DisplayName("P2-c: Course FK to professor is correctly persisted (ownership check)")
    void course_professorFkPersisted() throws Exception {
        // Ensure jwt() has run so persistedProfId and persistedCourseId are set
        jwt();
        assertThat(persistedCourseId).as("persistedCourseId must be set by Order(10)").isNotNull();
        assertThat(persistedProfId).as("persistedProfId must be set").isNotNull();

        // Fetch the course — use a separate repo call to avoid LazyInitialization on professor FK
        Optional<Course> courseOpt = courseRepository.findById(persistedCourseId);
        assertThat(courseOpt).isPresent();

        // Fetch the professor separately (avoids LAZY proxy outside session)
        Optional<Professor> profOpt = professorRepository.findById(persistedProfId);
        assertThat(profOpt).isPresent();
        assertThat(profOpt.get().getEmail()).isEqualTo(EMAIL);

        // The course's professor_id FK must equal the professor's id
        // We verify this by re-finding the course's professor through the repo (not LAZY proxy)
        Professor courseOwner = professorRepository.findById(
                courseOpt.get().getProfessor().getId()).orElseThrow();
        assertThat(courseOwner.getId()).isEqualTo(persistedProfId);
        assertThat(courseOwner.getEmail()).isEqualTo(EMAIL);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PART 3: STUDENT PERSISTENCE
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(20)
    @DisplayName("P3-a: Student added via endpoint is readable from repository in new transaction")
    void student_persistedAcrossTransactions() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/courses/" + persistedCourseId + "/students")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"CS24B001\",\"fullName\":\"Alice Kumar\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        persistedStudentId = UUID.fromString(body.get("id").asText());

        // New repo transaction: verify persisted
        assertThat(studentRepository.existsByCourseIdAndRollNumber(
                persistedCourseId, "CS24B001")).isTrue();

        Optional<Student> fromDb = studentRepository.findById(persistedStudentId);
        assertThat(fromDb).isPresent();
        assertThat(fromDb.get().getRollNumber()).isEqualTo("CS24B001");
        assertThat(fromDb.get().getFullName()).isEqualTo("Alice Kumar");
        assertThat(fromDb.get().getCourse().getId()).isEqualTo(persistedCourseId);
    }

    @Test @Order(21)
    @DisplayName("P3-b: Duplicate roll number rejected after first student is committed")
    void student_duplicateRollRejectedAfterCommit() throws Exception {
        mockMvc.perform(post("/api/courses/" + persistedCourseId + "/students")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"CS24B001\",\"fullName\":\"Alice Duplicate\"}"))
                .andExpect(status().isConflict());
    }

    @Test @Order(22)
    @DisplayName("P3-c: Student visible in GET /students after commit")
    void student_visibleViaHttpAfterCommit() throws Exception {
        mockMvc.perform(get("/api/courses/" + persistedCourseId + "/students")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.rollNumber == 'CS24B001')]").exists());
    }

    @Test @Order(23)
    @DisplayName("P3-d: studentCount in GET /api/courses/{id} reflects committed student")
    void student_countReflectedInCourse() throws Exception {
        mockMvc.perform(get("/api/courses/" + persistedCourseId)
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentCount").value(1));
    }

    @Test @Order(24)
    @DisplayName("P3-e: Deleted student is gone from DB in subsequent transaction")
    void student_deletedStudentGoneAfterCommit() throws Exception {
        // Add a throwaway student
        MvcResult result = mockMvc.perform(post("/api/courses/" + persistedCourseId + "/students")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"CS24B999\",\"fullName\":\"ToDelete\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID toDeleteId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        // Delete
        mockMvc.perform(delete("/api/students/" + toDeleteId)
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isNoContent());

        // New transaction: verify gone
        assertThat(studentRepository.findById(toDeleteId)).isEmpty();
        assertThat(studentRepository.existsByCourseIdAndRollNumber(
                persistedCourseId, "CS24B999")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    //  PART 4: ROSTER SYNC (Excel = Source of Truth)
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(30)
    @DisplayName("P4-a: Initial import — students not in Excel are removed (Excel is source of truth)")
    void roster_initialImportExcelIsSourceOfTruth() throws Exception {
        // DB has CS24B001. Excel has CS24B010–012 (no CS24B001).
        // CS24B001 must be removed since it's not in Excel.
        MvcResult result = mockMvc.perform(multipart(
                        "/api/courses/" + persistedCourseId + "/students/import")
                        .file(excelFile(new String[][]{
                                {"CS24B010", "Alice Ten"},
                                {"CS24B011", "Bob Eleven"},
                                {"CS24B012", "Carol Twelve"},
                        }))
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        // CS24B001 was in DB but NOT in Excel → removed
        assertThat(body.get("removed").size()).isEqualTo(1);
        // New students added
        assertThat(body.get("added").size()).isEqualTo(3);
        // Total = only what Excel says
        assertThat(body.get("totalAfterSync").asInt()).isEqualTo(3);

        // Verify directly in DB (new transaction)
        assertThat(studentRepository.existsByCourseIdAndRollNumber(
                persistedCourseId, "CS24B001")).isFalse();   // removed
        assertThat(studentRepository.existsByCourseIdAndRollNumber(
                persistedCourseId, "CS24B010")).isTrue();    // added
        assertThat(studentRepository.existsByCourseIdAndRollNumber(
                persistedCourseId, "CS24B011")).isTrue();
        assertThat(studentRepository.existsByCourseIdAndRollNumber(
                persistedCourseId, "CS24B012")).isTrue();
    }

    @Test @Order(31)
    @DisplayName("P4-b: Re-import with adds and removes — DB matches Excel exactly")
    void roster_reimportDbMatchesExcelExactly() throws Exception {
        // Current DB: CS24B010, CS24B011, CS24B012
        // New Excel:  CS24B011, CS24B012, CS24B013 (remove 010, add 013, keep 011+012)
        MvcResult result = mockMvc.perform(multipart(
                        "/api/courses/" + persistedCourseId + "/students/import")
                        .file(excelFile(new String[][]{
                                {"CS24B011", "Bob Eleven"},
                                {"CS24B012", "Carol Twelve"},
                                {"CS24B013", "Dave Thirteen"},
                        }))
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(body.get("added").size()).isEqualTo(1);      // CS24B013
        assertThat(body.get("removed").size()).isEqualTo(1);    // CS24B010
        assertThat(body.get("unchanged").asInt()).isEqualTo(2); // CS24B011, CS24B012
        assertThat(body.get("totalAfterSync").asInt()).isEqualTo(3);

        // DB must equal Excel exactly
        assertThat(studentRepository.findByCourseId(persistedCourseId)).hasSize(3);
        assertThat(studentRepository.existsByCourseIdAndRollNumber(
                persistedCourseId, "CS24B010")).isFalse();  // removed
        assertThat(studentRepository.existsByCourseIdAndRollNumber(
                persistedCourseId, "CS24B013")).isTrue();   // added
    }

    @Test @Order(32)
    @DisplayName("P4-c: Empty Excel import removes ALL students (Excel is complete source of truth)")
    void roster_emptyExcelRemovesAll() throws Exception {
        MvcResult result = mockMvc.perform(multipart(
                        "/api/courses/" + persistedCourseId + "/students/import")
                        .file(excelFile(new String[][]{}))
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(body.get("added").size()).isEqualTo(0);
        assertThat(body.get("totalAfterSync").asInt()).isEqualTo(0);
        assertThat(studentRepository.findByCourseId(persistedCourseId)).isEmpty();
    }

    @Test @Order(33)
    @DisplayName("P4-d: Name change in Excel does NOT update existing student name (sync is add/remove only)")
    void roster_nameChangeInExcelDoesNotUpdateDb() throws Exception {
        // First add a student manually
        mockMvc.perform(post("/api/courses/" + persistedCourseId + "/students")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"CS24B050\",\"fullName\":\"Original Name\"}"))
                .andExpect(status().isCreated());

        // Import Excel with same roll number but DIFFERENT name
        MvcResult result = mockMvc.perform(multipart(
                        "/api/courses/" + persistedCourseId + "/students/import")
                        .file(excelFile(new String[][]{{"CS24B050", "Changed Name"}}))
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        // Roll exists in both → counted as unchanged
        assertThat(body.get("unchanged").asInt()).isEqualTo(1);

        // DB name must still be "Original Name" — sync does NOT update existing records
        Student student = studentRepository.findByCourseId(persistedCourseId).stream()
                .filter(s -> s.getRollNumber().equals("CS24B050"))
                .findFirst().orElseThrow();
        assertThat(student.getFullName()).isEqualTo("Original Name");
    }

    // ═══════════════════════════════════════════════════════════════
    //  PART 5: SESSION PERSISTENCE
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(40)
    @DisplayName("P5-a: QrSession created via endpoint is readable from repository in new transaction")
    void session_persistedAcrossTransactions() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":\"" + persistedCourseId + "\"," +
                                "\"durationSeconds\":120}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        persistedSessionId = UUID.fromString(body.get("id").asText());

        // New transaction
        Optional<QrSession> fromDb = sessionRepository.findById(persistedSessionId);
        assertThat(fromDb).isPresent();
        assertThat(fromDb.get().getCourse().getId()).isEqualTo(persistedCourseId);
        assertThat(fromDb.get().getProfessor().getId()).isEqualTo(persistedProfId);
        assertThat(fromDb.get().getExpiresAt())
                .isAfter(Instant.now())
                .isBefore(Instant.now().plus(125, ChronoUnit.SECONDS));
        assertThat(fromDb.get().getClosedAt()).isNull();
    }

    @Test @Order(41)
    @DisplayName("P5-b: QR fetches do NOT mutate the session entity (expiresAt unchanged)")
    void session_notMutatedByQrFetches() throws Exception {
        Instant expiresAtBefore = sessionRepository.findById(persistedSessionId)
                .orElseThrow().getExpiresAt();

        // Fetch QR twice
        mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk());

        // Session entity must be unchanged in DB
        QrSession afterFetches = sessionRepository.findById(persistedSessionId).orElseThrow();
        assertThat(afterFetches.getExpiresAt()).isEqualTo(expiresAtBefore);
        assertThat(afterFetches.getClosedAt()).isNull();
    }

    @Test @Order(42)
    @DisplayName("P5-c: GET /api/sessions/{id} returns LIVE status while session active")
    void session_liveStatusWhileActive() throws Exception {
        mockMvc.perform(get("/api/sessions/" + persistedSessionId)
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.id").value(persistedSessionId.toString()))
                .andExpect(jsonPath("$.courseId").value(persistedCourseId.toString()));
    }

    @Test @Order(43)
    @DisplayName("P5-d: Force-expired session shows CLOSED via GET /api/sessions/{id}")
    void session_forcedExpiredShowsClosed() throws Exception {
        // Force expiry directly in DB (simulates 2 minutes passing)
        QrSession session = sessionRepository.findById(persistedSessionId).orElseThrow();
        session.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        sessionRepository.save(session);

        mockMvc.perform(get("/api/sessions/" + persistedSessionId)
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test @Order(44)
    @DisplayName("P5-e: QR endpoint on expired session → 409 Conflict (not 200)")
    void session_expiredSessionQrReturns409() throws Exception {
        // Session still expired from previous test
        mockMvc.perform(get("/api/sessions/" + persistedSessionId + "/qr")
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isConflict());
    }

    // ═══════════════════════════════════════════════════════════════
    //  PART 6: REFERENTIAL INTEGRITY / CASCADE DELETE
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(50)
    @DisplayName("P6-a: Deleting a course cascades and removes all its students from DB")
    void integrity_deleteCourseRemovesStudents() throws Exception {
        // Create a separate course just for this test
        MvcResult courseResult = mockMvc.perform(post("/api/courses")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cascade Test\",\"code\":\"CASC01\",\"semester\":\"2026\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID tempCourseId = UUID.fromString(
                objectMapper.readTree(courseResult.getResponse().getContentAsString())
                        .get("id").asText());

        // Add 2 students
        mockMvc.perform(post("/api/courses/" + tempCourseId + "/students")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"CA24B001\",\"fullName\":\"Cascade A\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/courses/" + tempCourseId + "/students")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"CA24B002\",\"fullName\":\"Cascade B\"}"))
                .andExpect(status().isCreated());

        // Confirm students exist
        assertThat(studentRepository.findByCourseId(tempCourseId)).hasSize(2);

        // Delete the course
        mockMvc.perform(delete("/api/courses/" + tempCourseId)
                        .header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isNoContent());

        // Verify cascade: students gone, course gone
        assertThat(studentRepository.findByCourseId(tempCourseId)).isEmpty();
        assertThat(courseRepository.findById(tempCourseId)).isEmpty();
    }
}
