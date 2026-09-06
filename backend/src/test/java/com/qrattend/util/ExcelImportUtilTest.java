package com.qrattend.util;

import com.qrattend.dto.student.StudentRequest;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExcelImportUtilTest {

    /**
     * Helper to create an in-memory .xlsx workbook with given headers and rows.
     */
    private ByteArrayInputStream createExcel(String rollHeader, String nameHeader, String[][] data) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Students");

            // Header row
            XSSFRow header = sheet.createRow(0);
            header.createCell(0).setCellValue(rollHeader);
            header.createCell(1).setCellValue(nameHeader);

            // Data rows
            for (int i = 0; i < data.length; i++) {
                XSSFRow row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(data[i][0]);
                row.createCell(1).setCellValue(data[i][1]);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Nested
    @DisplayName("Successful parsing")
    class SuccessfulParsing {

        @Test
        void parsesStandardHeaders() throws IOException {
            ByteArrayInputStream excel = createExcel("roll_number", "full_name", new String[][]{
                    {"CS24B001", "Alice"},
                    {"CS24B002", "Bob"},
                    {"CS24B003", "Charlie"}
            });

            List<StudentRequest> result = ExcelImportUtil.parseStudentExcel(excel);

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getRollNumber()).isEqualTo("CS24B001");
            assertThat(result.get(0).getFullName()).isEqualTo("Alice");
        }

        @Test
        void parsesAlternativeHeaders() throws IOException {
            ByteArrayInputStream excel = createExcel("Roll No", "Name", new String[][]{
                    {"CS24B001", "Alice"}
            });

            List<StudentRequest> result = ExcelImportUtil.parseStudentExcel(excel);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRollNumber()).isEqualTo("CS24B001");
        }

        @Test
        void trimsWhitespace() throws IOException {
            ByteArrayInputStream excel = createExcel("roll_number", "full_name", new String[][]{
                    {"  CS24B001  ", "  Alice  "}
            });

            List<StudentRequest> result = ExcelImportUtil.parseStudentExcel(excel);

            assertThat(result.get(0).getRollNumber()).isEqualTo("CS24B001");
            assertThat(result.get(0).getFullName()).isEqualTo("Alice");
        }

        @Test
        void deduplicatesByRollNumber() throws IOException {
            ByteArrayInputStream excel = createExcel("roll_number", "full_name", new String[][]{
                    {"CS24B001", "Alice"},
                    {"CS24B001", "Alice Duplicate"},
                    {"CS24B002", "Bob"}
            });

            List<StudentRequest> result = ExcelImportUtil.parseStudentExcel(excel);

            assertThat(result).hasSize(2);
            // First occurrence should be kept
            assertThat(result.get(0).getFullName()).isEqualTo("Alice");
        }

        @Test
        void skipsEmptyRows() throws IOException {
            ByteArrayInputStream excel = createExcel("roll_number", "full_name", new String[][]{
                    {"CS24B001", "Alice"},
                    {"", ""},
                    {"CS24B002", "Bob"}
            });

            List<StudentRequest> result = ExcelImportUtil.parseStudentExcel(excel);

            assertThat(result).hasSize(2);
        }

        @Test
        void handlesNumericRollNumbers() throws IOException {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                XSSFSheet sheet = workbook.createSheet("Students");
                XSSFRow header = sheet.createRow(0);
                header.createCell(0).setCellValue("roll_number");
                header.createCell(1).setCellValue("full_name");

                XSSFRow row = sheet.createRow(1);
                row.createCell(0).setCellValue(24001);  // Numeric cell
                row.createCell(1).setCellValue("Alice");

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                workbook.write(out);
                ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

                List<StudentRequest> result = ExcelImportUtil.parseStudentExcel(in);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getRollNumber()).isEqualTo("24001");
            }
        }
    }

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        void throwsOnMissingRollHeader() throws IOException {
            ByteArrayInputStream excel = createExcel("something_else", "full_name", new String[][]{
                    {"CS24B001", "Alice"}
            });

            assertThatThrownBy(() -> ExcelImportUtil.parseStudentExcel(excel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("roll number");
        }

        @Test
        void throwsOnMissingNameHeader() throws IOException {
            ByteArrayInputStream excel = createExcel("roll_number", "something_else", new String[][]{
                    {"CS24B001", "Alice"}
            });

            assertThatThrownBy(() -> ExcelImportUtil.parseStudentExcel(excel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("student name");
        }

        @Test
        void throwsOnEmptyDataRows() throws IOException {
            ByteArrayInputStream excel = createExcel("roll_number", "full_name", new String[][]{});

            assertThatThrownBy(() -> ExcelImportUtil.parseStudentExcel(excel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no valid student data");
        }
    }
}
