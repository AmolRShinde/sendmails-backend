package com.sendmail;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.Map;
import java.util.Properties;

@Service
public class EmailAsyncService {

    @Autowired
    private EmailService emailService;

    @Autowired
    private JobStatusStore jobStatusStore;

    @Autowired
    private SseEmitterService sseEmitterService;
	@Value("${spring.mail.username}")
    private String senderEmail;
    
    @Value("${spring.mail.password}")
    private String senderPassword;

    @Async
    public void processEmailsAsync(File excelFile, String jobId) {
        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            int totalRows = 0;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {  // start from 1 if first row is header
                Row row = sheet.getRow(i);
                if (row == null) continue; // skip null rows

                boolean hasData = false;
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null && cell.getCellType() != CellType.BLANK) {
                        hasData = true;
                        break;
                    }
                }
                if (hasData) totalRows++;
            }
            if (totalRows <= 0) {
                jobStatusStore.setProgress(jobId, 100);
                sseEmitterService.sendEvent(jobId, "complete", Map.of("message", "No rows to process"));
                sseEmitterService.complete(jobId);
                return;
            }

            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp.gmail.com");
            props.put("mail.smtp.port", "587");

            Session session = Session.getInstance(props, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(senderEmail, senderPassword);
                }
            });

            int processed = 0;
            JobStatus job = jobStatusStore.getJob(jobId);
            for (int i = 1; i <= totalRows; i++) {
                // Pause handling – blocks thread safely
                while (job != null && job.isPaused()) {
                    sseEmitterService.sendControl(jobId, "PAUSED");
                    Thread.sleep(300);
                }

                Row row = sheet.getRow(i);
                if (row == null) continue;
                String email = getCellString(row.getCell(0));
                String name = getCellString(row.getCell(1));
                String driveLink = row.getCell(2) != null ? getCellString(row.getCell(2)) : "";
                System.out.println("before -> " + driveLink);
                String attachPath = normalizeDriveLink(driveLink);
                System.out.println("after -> " + attachPath);
                jobStatusStore.setRowStatus(jobId, i, email, "PROCESSING");
                sseEmitterService.sendEvent(jobId, "row", Map.of("row", i, "email", email, "status", "PROCESSING"));

                try {
                    Message msg = new MimeMessage(session);
                    msg.setFrom(new InternetAddress(senderEmail));
                    msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email));
                    msg.setSubject("Your Subject");
                    MimeBodyPart textPart = new MimeBodyPart();
                    textPart.setText("Dear " + name + ",\n\nPlease find attached.\n\nRegards,");

                    Multipart multipart = new MimeMultipart();
                    multipart.addBodyPart(textPart);

                    if (attachPath != null && !attachPath.isBlank()) {
                        try {
                            File attachment = AttachmentDownloader.download(attachPath);
                            if (attachment != null && attachment.exists()) {
                                MimeBodyPart attachPart = new MimeBodyPart();
                                attachPart.attachFile(attachment);
                                attachPart.setFileName(attachment.getName());
                                multipart.addBodyPart(attachPart);
                            }
                        } catch (Exception ex) {
                            // record attachment download failure but continue
                            jobStatusStore.setRowStatus(jobId, i, email, "ATTACHMENT_FAILED");
                            sseEmitterService.sendEvent(jobId, "row", Map.of("row", i, "email", email, "status", "ATTACHMENT_FAILED"));
                        }
                    }

                    msg.setContent(multipart);
                    Transport.send(msg);

                    jobStatusStore.setRowStatus(jobId, i, email, "SENT");
                    sseEmitterService.sendEvent(jobId, "row", Map.of("row", i, "email", email, "status", "SENT"));

                } catch (Exception ex) {
                    jobStatusStore.setRowStatus(jobId, i, email, "FAILED: " + ex.getMessage());
                    sseEmitterService.sendEvent(jobId, "row", Map.of("row", i, "email", email, "status", "FAILED: " + ex.getMessage()));
                }

                processed++;
                int progress = (processed * 100) / totalRows;
                jobStatusStore.setProgress(jobId, progress);
                sseEmitterService.sendEvent(jobId, "progress", Map.of("progress", progress));

                Thread.sleep(150);
            }

            jobStatusStore.setProgress(jobId, 100);
            sseEmitterService.sendEvent(jobId, "progress", Map.of("progress", 100));
            sseEmitterService.sendEvent(jobId, "complete", Map.of("message", "Completed"));
            sseEmitterService.complete(jobId);

        } catch (Exception e) {
            e.printStackTrace();
            jobStatusStore.setProgress(jobId, -1);
            sseEmitterService.sendEvent(jobId, "error", Map.of("message", e.getMessage()));
            sseEmitterService.complete(jobId);
        }
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

    /**
     * Send email for a single row
     */
    public void sendRow(String jobId, JobStatus.RowStatus rs) {
        try {
            sendMail(rs.getEmail());
            rs.setStatus("SENT");
            jobStatusStore.setRowStatus(jobId, rs.getRow(), rs.getEmail(), rs.getStatus());
            sseEmitterService.sendRow(jobId, rs);
        } catch (Exception e) {
            rs.setStatus("FAILED: " + e.getMessage());
            jobStatusStore.setRowStatus(jobId, rs.getRow(), rs.getEmail(), rs.getStatus());
            sseEmitterService.sendRow(jobId, rs);
        }
    }

    /**
     * Actual sending logic (single email)
     */
    private void sendMail(String email) throws Exception {
        // Example: integrate with EmailService
        emailService.sendMail(
            email,
            "Your Subject Here",
            "Your email body here"
        );

    }

    public void retryRow(String jobId, int rowNum) {
        JobStatus job = jobStatusStore.getJob(jobId);
        JobStatus.RowStatus rs = jobStatusStore.getRow(jobId, rowNum);

        try {
            if (rs != null && "FAILED".equals(rs.getStatus())) {
                // Update status to retrying
                jobStatusStore.setRowStatus(jobId, rowNum, rs.getEmail(), "RETRYING");

                // resend mail
                sendMail(rs.getEmail());
                rs.setStatus("SENT");
            }
            
        } catch (Exception e) {
            rs.setStatus("FAILED");
        }

        sseEmitterService.sendRow(jobId, rs);
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

    @Async
    public void retryAllFailed(String jobId) {

        JobStatus job = jobStatusStore.getJob(jobId);
        if (job == null) return;

        for (JobStatus.RowStatus rs : job.getAllRowsNewestFirst()) {

            if (!"FAILED".equals(rs.getStatus())) {
                continue;
            }

            try {
                rs.setStatus("RETRYING");
                sseEmitterService.sendRow(jobId, rs);

                sendMail(rs.getEmail());

                rs.setStatus("SENT");
            } catch (Exception ex) {
                rs.setStatus("FAILED");
            }

            sseEmitterService.sendRow(jobId, rs);

            // small delay to avoid SMTP throttling
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {}
        }
    }

}
