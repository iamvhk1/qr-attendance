package com.qrattend.service;

import com.qrattend.entity.Attendance;
import com.qrattend.entity.QrSession;
import com.qrattend.exception.ForbiddenException;
import com.qrattend.exception.ResourceNotFoundException;
import com.qrattend.repository.AttendanceRepository;
import com.qrattend.repository.QrSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Generates Excel (.xlsx) attendance reports for closed sessions using Apache POI.
 *
 * <p>The report includes:</p>
 * <ul>
 *   <li>A header row with session metadata (course name, date, total students)</li>
 *   <li>A data table: Roll No | Name | Status | Heartbeat Coverage | Scanned At | Override Reason</li>
 *   <li>A summary row at the bottom with confirmed / invalidated / pending counts</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final QrSessionRepository sessionRepository;
    private final AttendanceRepository attendanceRepository;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Generates an Excel report for the given session.
     *
     * @param sessionId   the session UUID
     * @param professorId the authenticated professor's UUID (ownership check)
     * @return raw {@code .xlsx} bytes ready to send as a file download
     * @throws ResourceNotFoundException if the session does not exist
     * @throws ForbiddenException        if the professor does not own the session
     */
    @Transactional(readOnly = true)
    public byte[] generateExcel(UUID sessionId, UUID professorId) {
        QrSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));

        if (!session.getCourse().getProfessor().getId().equals(professorId)) {
            throw new ForbiddenException("You do not own session " + sessionId);
        }

        List<Attendance> records = attendanceRepository.findBySessionId(sessionId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Attendance");

            // ── Styles ──────────────────────────────────────────────
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 11);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle confirmedStyle = workbook.createCellStyle();
            confirmedStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            confirmedStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle invalidatedStyle = workbook.createCellStyle();
            invalidatedStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            invalidatedStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // ── Row 0: Title ─────────────────────────────────────────
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Attendance Report — " + session.getCourse().getName()
                    + " (" + session.getCourse().getCode() + ")");
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

            // ── Row 1: Session metadata ───────────────────────────────
            Row metaRow = sheet.createRow(1);
            metaRow.createCell(0).setCellValue("Session Date:");
            metaRow.createCell(1).setCellValue(TIME_FMT.format(session.getCreatedAt()));
            metaRow.createCell(3).setCellValue("Total Scanned:");
            metaRow.createCell(4).setCellValue(records.size());

            // ── Row 2: blank spacer ───────────────────────────────────
            sheet.createRow(2);

            // ── Row 3: Column headers ─────────────────────────────────
            String[] columns = { "Roll No.", "Name", "Status", "Coverage (%)", "Scanned At", "Override Reason" };
            Row colHeaderRow = sheet.createRow(3);
            for (int i = 0; i < columns.length; i++) {
                Cell cell = colHeaderRow.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            // ── Rows 4+: Data ─────────────────────────────────────────
            int rowIdx = 4;
            long confirmedCount = 0, invalidatedCount = 0, pendingCount = 0;

            for (Attendance att : records) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(att.getRollNumber());
                row.createCell(1).setCellValue(att.getStudentName() != null ? att.getStudentName() : "");

                Cell statusCell = row.createCell(2);
                statusCell.setCellValue(att.getStatus().name());
                switch (att.getStatus()) {
                    case CONFIRMED   -> { statusCell.setCellStyle(confirmedStyle);   confirmedCount++; }
                    case INVALIDATED -> { statusCell.setCellStyle(invalidatedStyle); invalidatedCount++; }
                    default          -> pendingCount++;
                }

                Cell coverageCell = row.createCell(3);
                if (att.getHeartbeatCoverage() != null) {
                    coverageCell.setCellValue(Math.round(att.getHeartbeatCoverage() * 100));
                } else {
                    coverageCell.setCellValue("—");
                }

                row.createCell(4).setCellValue(att.getMarkedAt() != null ? TIME_FMT.format(att.getMarkedAt()) : "");
                row.createCell(5).setCellValue(att.getOverrideReason() != null ? att.getOverrideReason() : "");
            }

            // ── Summary row ───────────────────────────────────────────
            sheet.createRow(rowIdx); // blank
            Row summaryRow = sheet.createRow(rowIdx + 1);
            summaryRow.createCell(0).setCellValue("Summary:");
            summaryRow.createCell(1).setCellValue("Confirmed: " + confirmedCount);
            summaryRow.createCell(2).setCellValue("Invalidated: " + invalidatedCount);
            summaryRow.createCell(3).setCellValue("Pending: " + pendingCount);

            // ── Auto-size columns ─────────────────────────────────────
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            log.info("Excel report generated for session {} ({} records)", sessionId, records.size());
            return out.toByteArray();

        } catch (IOException e) {
            log.error("Failed to generate Excel report for session {}: {}", sessionId, e.getMessage());
            throw new RuntimeException("Failed to generate Excel report", e);
        }
    }
}
