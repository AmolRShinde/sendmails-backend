package com.sendmail;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

@Service
public class EmailAsyncService {

    @Autowired
    private EmailService emailService;

    @Autowired
    private JobStatusStore jobStatusStore;

    @Autowired
    private SseEmitterService sseEmitterService;

    // =====================================================
    // MAIN ASYNC JOB
    // =====================================================

    @Async
    public void processEmailsAsync(File excelFile, String jobId) {

        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            int totalRows = countDataRows(sheet);

            if (totalRows == 0) {
                completeJob(jobId, "No rows to process");
                return;
            }

            JobStatus job = jobStatusStore.getJob(jobId);
            int processed = 0;

            for (int i = 1; i <= totalRows; i++) {

                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) {
                    continue;
                }

                // Pause support
                while (job != null && job.isPaused()) {
                    sseEmitterService.sendControl(jobId, "PAUSED");
                    Thread.sleep(300);
                }

                String email = getCellString(row.getCell(0));
                String name = getCellString(row.getCell(1));
                String driveLink = getCellString(row.getCell(2));
                String normalizedLink = normalizeDriveLink(driveLink);

                // Register row ONCE
                jobStatusStore.addRow(
                        jobId,
                        i,
                        email,
                        name,
                        normalizedLink,
                        "PROCESSING"
                );

                sseEmitterService.sendRow(jobId,
                        jobStatusStore.getRow(jobId, i));

                try {
                    sendInternal(jobId, i);
                    jobStatusStore.updateRowStatus(jobId, i, "SENT");
                } catch (Exception ex) {
                    jobStatusStore.updateRowStatus(
                            jobId,
                            i,
                            "FAILED: " + ex.getMessage()
                    );
                }

                sseEmitterService.sendRow(jobId,
                        jobStatusStore.getRow(jobId, i));

                processed++;
                int progress = (processed * 100) / totalRows;
                jobStatusStore.setProgress(jobId, progress);

                sseEmitterService.sendEvent(
                        jobId,
                        "progress",
                        Map.of("progress", progress)
                );

                Thread.sleep(150); // throttle SMTP
            }

            completeJob(jobId, "Completed");

        } catch (Exception e) {
            e.printStackTrace();
            jobStatusStore.setProgress(jobId, -1);
            sseEmitterService.sendEvent(
                    jobId,
                    "error",
                    Map.of("message", e.getMessage())
            );
            sseEmitterService.complete(jobId);
        }
    }

    // =====================================================
    // SINGLE SEND (USED EVERYWHERE)
    // =====================================================

    private void sendInternal(String jobId, int row) throws Exception {

        JobStatus.RowStatus rs = jobStatusStore.getRow(jobId, row);
        if (rs == null) {
            throw new IllegalStateException("Row not found: " + row);
        }

        File attachment = null;
        if (rs.getDriveLink() != null && !rs.getDriveLink().isBlank()) {
            attachment = AttachmentDownloader.download(rs.getDriveLink());
        }

        emailService.sendMail(
                rs.getEmail(),
                "Your Subject",
                "Dear " + rs.getName() + ",\n\nPlease find attached.\n\nRegards,",
                attachment != null ? attachment.getAbsolutePath() : null
        );
    }

    // =====================================================
    // MANUAL SEND (UI / API)
    // =====================================================

    public void sendRow(String jobId, JobStatus.RowStatus rs) {

        try {
            jobStatusStore.updateRowStatus(jobId, rs.getRow(), "PROCESSING");
            sseEmitterService.sendRow(jobId, rs);

            sendInternal(jobId, rs.getRow());

            jobStatusStore.updateRowStatus(jobId, rs.getRow(), "SENT");

        } catch (Exception e) {
            jobStatusStore.updateRowStatus(
                    jobId,
                    rs.getRow(),
                    "FAILED: " + e.getMessage()
            );
        }

        sseEmitterService.sendRow(
                jobId,
                jobStatusStore.getRow(jobId, rs.getRow())
        );
    }

    // =====================================================
    // RETRY SINGLE ROW
    // =====================================================

    @Async
    public void retryRow(String jobId, int row) {

        try {
            jobStatusStore.updateRowStatus(jobId, row, "RETRYING");
            sseEmitterService.sendRow(jobId,
                    jobStatusStore.getRow(jobId, row));

            sendInternal(jobId, row);

            jobStatusStore.updateRowStatus(jobId, row, "SENT");

        } catch (Exception e) {
            jobStatusStore.updateRowStatus(
                    jobId,
                    row,
                    "FAILED: " + e.getMessage()
            );
        }

        sseEmitterService.sendRow(
                jobId,
                jobStatusStore.getRow(jobId, row)
        );
    }

    // =====================================================
    // RETRY ALL FAILED
    // =====================================================

    @Async
    public void retryAllFailed(String jobId) {

        JobStatus job = jobStatusStore.getJob(jobId);
        if (job == null) return;

        for (JobStatus.RowStatus rs : job.getAllRowsNewestFirst()) {

            if (!rs.getStatus().startsWith("FAILED")) {
                continue;
            }

            try {
                jobStatusStore.updateRowStatus(
                        jobId,
                        rs.getRow(),
                        "RETRYING"
                );

                sseEmitterService.sendRow(jobId,
                        jobStatusStore.getRow(jobId, rs.getRow()));

                sendInternal(jobId, rs.getRow());

                jobStatusStore.updateRowStatus(jobId, rs.getRow(), "SENT");

            } catch (Exception e) {
                jobStatusStore.updateRowStatus(
                        jobId,
                        rs.getRow(),
                        "FAILED: " + e.getMessage()
                );
            }

            sseEmitterService.sendRow(
                    jobId,
                    jobStatusStore.getRow(jobId, rs.getRow())
            );

            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        }
    }

    // =====================================================
    // HELPERS
    // =====================================================

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

    private void completeJob(String jobId, String message) {
        jobStatusStore.setProgress(jobId, 100);
        sseEmitterService.sendEvent(
                jobId,
                "complete",
                Map.of("message", message)
        );
        sseEmitterService.complete(jobId);
    }

    public static String normalizeDriveLink(String url) {
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

    private String getCellString(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> String.valueOf((long) c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }
}
