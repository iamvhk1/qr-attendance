package com.qrattend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    /**
     * Mirrors Spring Boot's own switch for the H2 console. The console path is only
     * opened up (and framing allowed) when the console is actually enabled, which is
     * local development only — {@code application-prod.properties} sets it to false.
     */
    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> {
                // H2 DB console — only opened when the console is enabled (local dev).
                // In production it is disabled, so this path falls through to
                // anyRequest().authenticated() below.
                if (h2ConsoleEnabled) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }
                auth
                // Public endpoints
                .requestMatchers("/api/auth/**").permitAll() // Login / Register
                .requestMatchers("/", "/health").permitAll() // Render health check
                // Role specific
                .requestMatchers("/api/professor/**").hasRole("PROFESSOR")
                .requestMatchers("/api/courses/**").hasRole("PROFESSOR")
                .requestMatchers("/api/students/**").hasRole("PROFESSOR")
                .requestMatchers("/api/sessions/**").hasRole("PROFESSOR")
                .requestMatchers("/api/reports/**").hasRole("PROFESSOR")
                // Scan token protected
                .requestMatchers("/api/student/scan").hasRole("SCAN")
                .requestMatchers("/api/student/heartbeat").hasRole("ATTENDANCE")
                .requestMatchers("/api/student/doubt").hasRole("ATTENDANCE")
                // Any other request must be authenticated
                .anyRequest().authenticated();
            });

        // The H2 console uses frames, so allow same-origin framing only when it is enabled.
        // Otherwise keep Spring Security's default X-Frame-Options: DENY (clickjacking protection).
        if (h2ConsoleEnabled) {
            http.headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));
        }

        // Add JWT filter before the standard authentication filter
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:3000,http://192.168.0.193:5173}")
    private List<String> allowedOrigins;

    /**
     * CORS configuration — allows the Vite dev frontend (localhost:5173) and any
     * local dev server (localhost:3000) to make cross-origin requests.
     *
     * <p>In production, replace {@code allowedOrigins} with your actual deployed
     * frontend URL (e.g. {@code https://your-app.vercel.app}).</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L); // Cache preflight response for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
