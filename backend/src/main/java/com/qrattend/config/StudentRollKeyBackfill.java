package com.qrattend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fills {@code students.roll_key} (the upper-cased roll number that backs the case-insensitive
 * uniqueness rule) for rows created before that column existed.
 *
 * <p>{@code ddl-auto=update} adds the column and its unique constraint but leaves existing rows
 * with a null key, which the constraint allows. This runner sets the key for every such row,
 * except where two students of the same course already share a roll number ignoring letter case:
 * only the oldest of those gets the key, so the constraint can never be violated by this backfill.
 * The others keep a null key; they are logged so the professor can remove the extra student.</p>
 *
 * <p>It never stops the application from starting: any failure is logged and start-up continues,
 * and it does nothing when every row already has a key, so it is safe to leave in place.</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class StudentRollKeyBackfill implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long missing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM students WHERE roll_key IS NULL", Long.class);
            if (missing == null || missing == 0) {
                return;
            }

            int filled = jdbcTemplate.update(
                    "UPDATE students SET roll_key = UPPER(TRIM(roll_number)) "
                            + "WHERE roll_key IS NULL "
                            // never take a key another student of the course already holds
                            + "  AND NOT EXISTS (SELECT 1 FROM students k "
                            + "    WHERE k.course_id = students.course_id "
                            + "      AND k.roll_key = UPPER(TRIM(students.roll_number))) "
                            + "  AND NOT EXISTS ("
                            + "  SELECT 1 FROM students o "
                            + "  WHERE o.course_id = students.course_id "
                            + "    AND UPPER(TRIM(o.roll_number)) = UPPER(TRIM(students.roll_number)) "
                            + "    AND o.id <> students.id "
                            + "    AND (o.created_at < students.created_at "
                            + "         OR (o.created_at = students.created_at AND o.id < students.id)))");
            if (filled > 0) {
                log.info("Filled students.roll_key for {} existing student(s)", filled);
            }

            Long left = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM students WHERE roll_key IS NULL", Long.class);
            if (left != null && left > 0) {
                log.warn("{} student row(s) share a roll number with another student of the same course "
                        + "(ignoring letter case). The one-student-per-roll-number rule is not enforced "
                        + "for them; delete the extra students to fix this.", left);
            }
        } catch (Exception e) {
            log.error("Could not backfill students.roll_key; continuing start-up: {}", e.getMessage());
        }
    }
}
