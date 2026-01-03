package com.sendmail.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;

import com.sendmail.job.JobStatusStore;
import com.sendmail.job.JobStatus;
import com.sendmail.AttachmentDownloader;
import com.sendmail.util.EmailTemplateUtil;


@Service
public class EmailAsyncService {

    @Autowired
    private EmailService emailService;

    @Autowired
    private JobStatusStore jobStatusStore;

    @Autowired
    private SseEmitterService sseEmitterService;

    private static final int STATUS_COL = 7; // Column after driveLink

    @Async
    public void processEmailsAsync(File excelFile, String jobId) {

        try (Workbook workbook = new XSSFWorkbook(new FileInputStream(excelFile))) {

            Sheet sheet = workbook.getSheetAt(0);
            ensureStatusHeader(sheet); // add status column
            int totalRows = countDataRows(sheet);

            if (totalRows == 0) {
                complete(jobId, "No rows with data");
                return;
            }

            int processed = 0;
            JobStatus job = jobStatusStore.getJob(jobId);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {

                // -------- Pause handling --------
                while (job != null && job.isPaused()) {
                    sseEmitterService.sendControl(jobId, "PAUSED");
                    Thread.sleep(300);
                }

                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) {
                    continue;
                }

                // as per excel file - start
                String name = getCellString(row.getCell(5)); //flat no
                String email = getCellString(row.getCell(1)); //receipent email address
                String rawDriveLink = getCellString(row.getCell(6)); //attachment path
                // -end
               // String email = getCellString(row.getCell(0));
                //String name = getCellString(row.getCell(1));
                //String rawDriveLink = getCellString(row.getCell(2));
                String driveLink = normalizeDriveLink(rawDriveLink);

                // -------- Mark PROCESSING --------
                jobStatusStore.addOrUpdateRow(
                        jobId,
                        i,
                        email,
                        name,
                        driveLink,
                        "PROCESSING"
                );

                sseEmitterService.sendRow(
                        jobId,
                        jobStatusStore.getRow(jobId, i)
                );

                try {
                    File attachment = null;
                    if (driveLink != null && !driveLink.isBlank()) {
                        attachment = AttachmentDownloader.download(driveLink);
                    }

                    //load subject and body from template
                    String subject = EmailTemplateUtil.load(
                        "subject.txt",
                        Map.of()
                    );

                    String body = EmailTemplateUtil.load(
                        "email.html",
                        Map.of("name", name)
                    );
                    // end

                    emailService.sendMail(email, subject, body, attachment);

                    updateExcelStatus(workbook, sheet, i, "SENT");

                    jobStatusStore.updateRowStatus(jobId, i, "SENT");

                } catch (Exception ex) {
                    String err = ex.getMessage();
                    updateExcelStatus(workbook, sheet, i, "FAILED: " + err);

                    jobStatusStore.updateRowStatus(
                            jobId,
                            i,
                            "FAILED: " + ex.getMessage()
                    );
                }

                try (FileOutputStream fos = new FileOutputStream(excelFile)) {
                    workbook.write(fos);
                }

                // -------- Push updated row --------
                sseEmitterService.sendRow(
                        jobId,
                        jobStatusStore.getRow(jobId, i)
                );

                processed++;
                int progress = (processed * 100) / totalRows;
                jobStatusStore.setProgress(jobId, progress);

                sseEmitterService.sendEvent(
                        jobId,
                        "progress",
                        Map.of("progress", progress)
                );

                Thread.sleep(200); // throttle
            }

            // -------- Final completion --------
            jobStatusStore.setProgress(jobId, 100);
            sseEmitterService.sendEvent(
                    jobId,
                    "progress",
                    Map.of("progress", 100)
            );
            complete(jobId, "Completed");

        } catch (Exception e) {
            complete(jobId, "Error: " + e.getMessage());
        }
    }

    // ---------------- HELPERS ----------------

    private void ensureStatusHeader(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) header = sheet.createRow(0);

        Cell cell = header.getCell(STATUS_COL);
        if (cell == null) {
            cell = header.createCell(STATUS_COL);
            cell.setCellValue("Status");
        }
    }

    private void updateExcelStatus(
        Workbook workbook,
        Sheet sheet,
        int rowIndex,
        String status
    ) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) return;

        int STATUS_COL = row.getLastCellNum(); // append new column
        Cell cell = row.getCell(STATUS_COL);
        if (cell == null) {
            cell = row.createCell(STATUS_COL);
        }

        cell.setCellValue(status);

        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);

        if (status.startsWith("SENT")) {
            style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            font.setColor(IndexedColors.DARK_GREEN.getIndex());
        } else {
            style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            font.setColor(IndexedColors.DARK_RED.getIndex());
        }

        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFont(font);
        cell.setCellStyle(style);
    }



    private int countDataRows(Sheet sheet) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !isRowEmpty(row)) {
                count++;
            }
        }
        return count;
    }

    private boolean isRowEmpty(Row row) {
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private void complete(String jobId, String message) {
        sseEmitterService.sendEvent(
                jobId,
                "complete",
                Map.of("message", message)
        );
        sseEmitterService.complete(jobId);
    }

    /**
     * Normalize Google Drive links to downloadable sharing links
     */
    private String normalizeDriveLink(String url) {
        if (url == null) return null;

        if (url.contains("drive.google.com/file/d/")) {
            int idStart = url.indexOf("/d/") + 3;
            int idEnd = url.indexOf("/", idStart);
            if (idEnd > idStart) {
                String fileId = url.substring(idStart, idEnd);
                return "https://drive.google.com/file/d/" + fileId + "/view?usp=sharing";
            }
        }
        return url;
    }
}
