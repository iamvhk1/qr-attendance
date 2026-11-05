package com.qrattend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Removes the old 20-character limit on {@code courses.code} in databases created
 * before the limit was lifted. {@code ddl-auto=update} adds columns but never changes
 * an existing column's type, so without this the live table would keep VARCHAR(20).
 *
 * <p>Runs once per startup and does nothing when the column is already unbounded
 * (or wide), so it is safe to leave in place.</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class CourseCodeColumnMigration implements ApplicationRunner {

    /** Columns at or below this width are treated as the old, limited type. */
    static final int OLD_LIMIT_THRESHOLD = 255;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Long> lengths = jdbcTemplate.queryForList(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = 'courses' AND LOWER(column_name) = 'code'",
                Long.class);
        if (lengths.isEmpty()) {
            return; // table not created (yet) — nothing to migrate
        }
        Long current = lengths.get(0);
        if (current == null || current > OLD_LIMIT_THRESHOLD) {
            return; // already unbounded
        }
        // SET DATA TYPE is valid on both Postgres and H2; VARCHAR without a length is
        // unbounded on Postgres. NOT NULL is kept.
        jdbcTemplate.execute("ALTER TABLE courses ALTER COLUMN code SET DATA TYPE VARCHAR");
        log.info("Widened courses.code from VARCHAR({}) to unbounded VARCHAR", current);
    }
}
