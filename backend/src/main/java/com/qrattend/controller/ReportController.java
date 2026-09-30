package com.qrattend.controller;

import com.qrattend.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * REST controller for generating and downloading attendance reports.
 *
 * <p>All endpoints are protected by {@code ROLE_PROFESSOR} (enforced globally
 * in {@code SecurityConfig} via the {@code /api/reports/**} pattern).</p>
 *
 * <p>Reports are streamed as file downloads using {@code Content-Disposition: attachment}.
 * The frontend must use a fetch-to-blob approach (not a plain anchor tag) to attach the
 * professor's {@code Authorization: Bearer} header to the download request.</p>
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    // ── GET /api/reports/sessions/{id}/excel ─────────────────

    /**
     * Generates and streams an Excel (.xlsx) attendance report for the given session.
     *
     * <p>The file is named {@code attendance-{sessionId}-{date}.xlsx} and sent as a
     * binary download with {@code Content-Disposition: attachment}.</p>
     *
     * @return 200 OK with raw {@code .xlsx} bytes
     */
    @GetMapping("/sessions/{id}/excel")
    public ResponseEntity<byte[]> downloadExcel(@PathVariable UUID id) {
        UUID professorId = getProfessorId();
        byte[] bytes = reportService.generateExcel(id, professorId);

        String filename = "attendance-" + id + "-" + LocalDate.now() + ".xlsx";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(bytes.length);

        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    // ── Helper ───────────────────────────────────────────────

    private UUID getProfessorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
