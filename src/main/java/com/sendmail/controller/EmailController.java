package com.sendmail.controller;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.sendmail.job.JobStatusStore;
import com.sendmail.job.JobStatus;
import com.sendmail.service.EmailAsyncService;
import com.sendmail.service.SseEmitterService;

@RestController
@RequestMapping("/api/email")
@CrossOrigin(origins = "*") // IMPORTANT for Render
public class EmailController {

    @Autowired
    private EmailAsyncService emailAsyncService;

    @Autowired
    private JobStatusStore jobStatusStore;

    @Autowired
    private SseEmitterService sseEmitterService;

    // ---------------- SEND ----------------

    @PostMapping("/send-async")
    public ResponseEntity<Map<String, String>> sendAsync(
            @RequestParam("file") MultipartFile file) throws Exception {

        String jobId = UUID.randomUUID().toString();
        jobStatusStore.createJob(jobId);

        File temp = File.createTempFile("upload_", ".xlsx");
        file.transferTo(temp);

        emailAsyncService.processEmailsAsync(temp, jobId);

        return ResponseEntity.ok(Map.of("jobId", jobId));
    }

    // ---------------- SSE ----------------

    @GetMapping("/stream/{jobId}")
    public SseEmitter stream(@PathVariable String jobId) {
        return sseEmitterService.createEmitter(jobId);
    }

    // ---------------- PAUSE / RESUME ----------------

    @PostMapping("/pause/{jobId}")
    public ResponseEntity<?> pauseJob(@PathVariable String jobId) {
        JobStatus job = jobStatusStore.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        job.setPaused(true);
        sseEmitterService.sendControl(jobId, "PAUSED");
        return ResponseEntity.ok("PAUSED");
    }

    @PostMapping("/resume/{jobId}")
    public ResponseEntity<?> resumeJob(@PathVariable String jobId) {
        JobStatus job = jobStatusStore.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        job.setPaused(false);
        sseEmitterService.sendControl(jobId, "RESUMED");
        return ResponseEntity.ok("RESUMED");
    }

    // ---------------- STATUS ----------------

    @GetMapping("/status/{jobId}")
    public ResponseEntity<JobStatus> getStatus(@PathVariable String jobId) {
        JobStatus js = jobStatusStore.getJob(jobId);
        if (js == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(js);
    }

    // ---------------- REPORT ----------------

    @GetMapping("/report/{jobId}")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String jobId) {

        JobStatus job = jobStatusStore.getJob(jobId);
        if (job == null) {
            return ResponseEntity.status(404)
                    .body(("Job not found: " + jobId)
                            .getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Row,Email,Status\n");

        for (JobStatus.RowStatus r : job.getRowStatusList()) {
            sb.append(r.getRow()).append(",")
              .append('"').append(r.getEmail()).append('"').append(",")
              .append('"').append(r.getStatus()).append('"')
              .append("\n");
        }

        byte[] csv = sb.toString().getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=report-" + jobId + ".csv");

        return ResponseEntity.ok().headers(headers).body(csv);
    }

    // ---------------- PREVIEW ----------------

    @PostMapping("/preview")
    public List<Map<String, String>> previewExcel(
            @RequestParam("file") MultipartFile file) throws Exception {

        List<Map<String, String>> preview = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            int count = 0;
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                if (count >= 10) break;

                Cell emailCell = row.getCell(0);
                if (emailCell == null) continue;

                preview.add(Map.of(
                        "row", String.valueOf(row.getRowNum()),
                        "email", emailCell.getStringCellValue()
                ));
                count++;
            }
        }
        return preview;
    }

    // ---------------- RETRY ALL (SAFE PLACEHOLDER) ----------------

    @PostMapping("/retry-all/{jobId}")
    public ResponseEntity<?> retryAll(@PathVariable String jobId) {
        return ResponseEntity.ok("RETRY_ALL_NOT_IMPLEMENTED_YET");
    }
}
