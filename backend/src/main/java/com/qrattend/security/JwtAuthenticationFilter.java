package com.qrattend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Intercepts incoming HTTP requests to extract and validate JWT tokens.
 * <p>
 * If a valid token is found in the {@code Authorization} header, it populates
 * the Spring Security context, allowing the request to proceed to protected endpoints.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            String jwt = parseJwt(request);

            if (jwt != null && !jwtUtil.isTokenExpired(jwt)) {
                
                // Handle Professor Login Tokens
                if (jwtUtil.isLoginToken(jwt)) {
                    UUID professorId = jwtUtil.extractProfessorId(jwt);
                    
                    // In a stateless JWT setup, we trust the token if it's signed and valid.
                    // We don't need to hit the database to load the user on every request.
                    UsernamePasswordAuthenticationToken authentication = 
                            new UsernamePasswordAuthenticationToken(
                                    professorId, 
                                    null, 
                                    List.of(new SimpleGrantedAuthority("ROLE_PROFESSOR")));
                                    
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } 
                // Handle Student Scan Tokens
                else if (jwtUtil.isScanToken(jwt)) {
                    UUID sessionId = jwtUtil.extractSessionId(jwt);
                    
                    UsernamePasswordAuthenticationToken authentication = 
                            new UsernamePasswordAuthenticationToken(
                                    sessionId, 
                                    null, 
                                    List.of(new SimpleGrantedAuthority("ROLE_SCAN")));
                                    
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
                // Handle Student Attendance Tokens (for heartbeat pings)
                else if (jwtUtil.isAttendanceToken(jwt)) {
                    UUID attendanceId = jwtUtil.extractAttendanceId(jwt);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    attendanceId,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ATTENDANCE")));

                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception e) {
            log.error("Cannot set user authentication: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }

        return null;
    }
}
