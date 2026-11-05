package com.qrattend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the production hardening in {@code application-prod.properties} and
 * {@code SecurityConfig}, by booting the real application with the {@code prod} profile.
 *
 * <p>Supabase is swapped for an in-memory H2 database; everything else —
 * including the real {@code application-prod.properties} — is loaded as in production.</p>
 */
@DisplayName("Production configuration")
class ProductionConfigTest {

    /** Stands in for Supabase so the prod profile can boot without a real Postgres. */
    private static final String[] IN_MEMORY_DB = {
            "--spring.datasource.url=jdbc:h2:mem:prodcfg_startup;DB_CLOSE_DELAY=-1",
            "--spring.datasource.username=sa",
            "--spring.datasource.password=",
            "--spring.datasource.driver-class-name=org.h2.Driver",
            "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "--server.port=0",
    };

    private static final String TEST_JWT_SECRET =
            "cHJvZC10ZXN0LXNlY3JldC1rZXktdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTY=";
    private static final String TEST_BREVO_KEY = "xkeysib-prod-test-key";
    private static final String TEST_MAIL_FROM = "noreply@iitm.ac.in";

    /** Boots the app with the prod profile and the given extra arguments; caller must close it. */
    private static ConfigurableApplicationContext bootProd(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(IN_MEMORY_DB));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(QrAttendanceApplication.class)
                .profiles("prod")
                .run(args.toArray(String[]::new));
    }

    // ══════════════════════════════════════════════════════════════
    //  Fix 1 — no fallback secrets in production
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Fix 1 — secrets have no fallback in production")
    class SecretsRequired {

        @Test
        @DisplayName("Refuses to start when JWT_SECRET is not set")
        void failsWithoutJwtSecret() {
            assumeTrue(System.getenv("JWT_SECRET") == null, "JWT_SECRET is set in this environment");

            assertThatThrownBy(() -> bootProd(
                    "--BREVO_API_KEY=" + TEST_BREVO_KEY, "--MAIL_FROM=" + TEST_MAIL_FROM).close())
                    .hasStackTraceContaining("Could not resolve placeholder 'JWT_SECRET'");
        }

        @Test
        @DisplayName("Refuses to start when BREVO_API_KEY is not set")
        void failsWithoutBrevoKey() {
            assumeTrue(System.getenv("BREVO_API_KEY") == null, "BREVO_API_KEY is set in this environment");

            assertThatThrownBy(() -> bootProd(
                    "--JWT_SECRET=" + TEST_JWT_SECRET, "--MAIL_FROM=" + TEST_MAIL_FROM).close())
                    .hasStackTraceContaining("Could not resolve placeholder 'BREVO_API_KEY'");
        }

        @Test
        @DisplayName("Refuses to start when MAIL_FROM is not set")
        void failsWithoutMailFrom() {
            assumeTrue(System.getenv("MAIL_FROM") == null, "MAIL_FROM is set in this environment");

            assertThatThrownBy(() -> bootProd(
                    "--JWT_SECRET=" + TEST_JWT_SECRET, "--BREVO_API_KEY=" + TEST_BREVO_KEY).close())
                    .hasStackTraceContaining("Could not resolve placeholder 'MAIL_FROM'");
        }

        @Test
        @DisplayName("Starts with all three set: uses them, sends email via Brevo, no longer needs ADMIN_SECRET")
        void startsWithAllSecrets() {
            assumeTrue(System.getenv("ADMIN_SECRET") == null, "ADMIN_SECRET is set in this environment");

            try (ConfigurableApplicationContext ctx = bootProd(
                    "--JWT_SECRET=" + TEST_JWT_SECRET,
                    "--BREVO_API_KEY=" + TEST_BREVO_KEY,
                    "--MAIL_FROM=" + TEST_MAIL_FROM)) {

                Environment env = ctx.getEnvironment();
                assertThat(env.getProperty("app.jwt.secret")).isEqualTo(TEST_JWT_SECRET);
                assertThat(env.getProperty("app.mail.provider")).isEqualTo("brevo");
                assertThat(env.getProperty("app.mail.brevo.api-key")).isEqualTo(TEST_BREVO_KEY);
                assertThat(env.getProperty("app.mail.from-address")).isEqualTo(TEST_MAIL_FROM);

                // The real sender in production is Brevo; the console-logging sender is not created
                assertThat(ctx.getBeansOfType(com.qrattend.mail.BrevoEmailSender.class)).hasSize(1);
                assertThat(ctx.getBeansOfType(com.qrattend.mail.LoggingEmailSender.class)).isEmpty();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Fix 2 — H2 console off in production
    // ══════════════════════════════════════════════════════════════

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("prod")
    @TestPropertySource(properties = {
            "spring.datasource.url=jdbc:h2:mem:prodcfg_web;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "JWT_SECRET=" + TEST_JWT_SECRET,
            "BREVO_API_KEY=" + TEST_BREVO_KEY,
            "MAIL_FROM=" + TEST_MAIL_FROM,
    })
    @DisplayName("Fix 2 — H2 console in production")
    class H2ConsoleInProd {

        @Autowired private MockMvc mockMvc;
        @Autowired private ApplicationContext ctx;
        @Autowired private Environment env;

        @Test
        @DisplayName("Console is disabled by the prod profile")
        void consoleDisabled() {
            assertThat(env.getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
        }

        @Test
        @DisplayName("No H2 console servlet is registered")
        void noConsoleServletRegistered() {
            boolean consoleRegistered = ctx.getBeansOfType(ServletRegistrationBean.class).values().stream()
                    .map(r -> (ServletRegistrationBean<?>) r)
                    .flatMap(r -> r.getUrlMappings().stream())
                    .anyMatch(u -> u.startsWith("/h2-console"));
            assertThat(consoleRegistered).isFalse();
        }

        @Test
        @DisplayName("/h2-console is no longer public — unauthenticated request → 401")
        void consolePathRequiresAuth() throws Exception {
            mockMvc.perform(get("/h2-console"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/h2-console/login.do"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Responses keep X-Frame-Options: DENY (framing not opened up)")
        void framingDenied() throws Exception {
            mockMvc.perform(get("/health"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }

        @Test
        @DisplayName("Public and protected endpoints still behave normally")
        void otherRulesUnchanged() throws Exception {
            mockMvc.perform(get("/health")).andExpect(status().isOk());
            mockMvc.perform(get("/api/courses")).andExpect(status().isUnauthorized());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Fix 2 — local development keeps a working console
    // ══════════════════════════════════════════════════════════════

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "spring.h2.console.enabled=true",
            "spring.h2.console.path=/h2-console",
    })
    @DisplayName("Fix 2 — H2 console in local development")
    class H2ConsoleInDev {

        @Autowired private MockMvc mockMvc;

        @Test
        @DisplayName("/h2-console stays public when the console is enabled")
        void consolePathIsPublic() throws Exception {
            int status = mockMvc.perform(get("/h2-console/login.do"))
                    .andReturn().getResponse().getStatus();
            // MockMvc only drives the DispatcherServlet, not H2's own servlet, so we
            // can't see the console page itself — only that security let the request through.
            assertThat(status).isNotIn(401, 403);
        }

        @Test
        @DisplayName("Same-origin framing is allowed so the console's frames work")
        void sameOriginFramingAllowed() throws Exception {
            mockMvc.perform(get("/health"))
                    .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
        }
    }
}
