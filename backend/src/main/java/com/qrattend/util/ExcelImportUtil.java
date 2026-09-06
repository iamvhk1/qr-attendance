package com.qrattend.util;

import com.qrattend.dto.student.StudentRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility for parsing student data from Excel (.xlsx) files.
 * <p>
 * Supports flexible header detection (e.g., "roll_number", "Roll No", "rollnumber")
 * and handles both STRING and NUMERIC cell types.
 */
public class ExcelImportUtil {

    // Acceptable header names (lowercased) for the roll number column
    private static final Set<String> ROLL_HEADERS = Set.of(
            "roll_number", "rollnumber", "roll", "roll no", "roll_no", "rollno"
    );

    // Acceptable header names (lowercased) for the name column
    private static final Set<String> NAME_HEADERS = Set.of(
            "full_name", "fullname", "name", "student_name", "studentname", "student name"
    );

    private ExcelImportUtil() {
        // Utility class — prevent instantiation
    }

    /**
     * Parses an Excel (.xlsx) file and returns a list of student requests.
     *
     * @param inputStream the Excel file input stream
     * @return deduplicated list of parsed students
     * @throws IllegalArgumentException if the file is invalid, empty, or missing required headers
     */
    public static List<StudentRequest> parseStudentExcel(InputStream inputStream) {
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new IllegalArgumentException("Excel file is empty");
            }

            // --- Find the header row ---
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException("Excel file has no header row");
            }

            int rollCol = -1;
            int nameCol = -1;

            for (Cell cell : headerRow) {
                String headerValue = getCellStringValue(cell).toLowerCase().trim();
                if (ROLL_HEADERS.contains(headerValue)) {
                    rollCol = cell.getColumnIndex();
                } else if (NAME_HEADERS.contains(headerValue)) {
                    nameCol = cell.getColumnIndex();
                }
            }

            if (rollCol == -1) {
                throw new IllegalArgumentException(
                        "Missing required column: roll number. Expected one of: " + ROLL_HEADERS);
            }
            if (nameCol == -1) {
                throw new IllegalArgumentException(
                        "Missing required column: student name. Expected one of: " + NAME_HEADERS);
            }

            // --- Parse data rows ---
            List<StudentRequest> students = new ArrayList<>();
            Set<String> seenRollNumbers = new LinkedHashSet<>();

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String rollNumber = getCellStringValue(row.getCell(rollCol)).trim();
                String fullName = getCellStringValue(row.getCell(nameCol)).trim();

                // Skip empty rows
                if (rollNumber.isEmpty() || fullName.isEmpty()) continue;

                // Deduplicate by roll number (keep first occurrence)
                if (seenRollNumbers.add(rollNumber)) {
                    students.add(StudentRequest.builder()
                            .rollNumber(rollNumber)
                            .fullName(fullName)
                            .build());
                }
            }

            if (students.isEmpty()) {
                throw new IllegalArgumentException("Excel file contains no valid student data rows");
            }

            return students;

        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read Excel file: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a cell value as a String, handling STRING, NUMERIC, BOOLEAN, and FORMULA types.
     */
    private static String getCellStringValue(Cell cell) {
        if (cell == null) return "";

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                // Avoid scientific notation for numbers like roll numbers
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }
}
