package com.sendmail;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.util.*;


@RestController
@RequestMapping("/api/email")
@CrossOrigin(origins = "http://localhost:3000")
public class EmailController {

    @Autowired private EmailAsyncService emailAsyncService;
    @Autowired private JobStatusStore jobStatusStore;
    @Autowired private SseEmitterService sseEmitterService;

    @PostMapping("/send-async")
    public ResponseEntity<Map<String,String>> sendAsync(@RequestParam("file") MultipartFile file) throws Exception {
        String jobId = UUID.randomUUID().toString();
        jobStatusStore.createJob(jobId);

        File temp = File.createTempFile("upload_", ".xlsx");
        file.transferTo(temp);

        

        emailAsyncService.processEmailsAsync(temp, jobId);

        return ResponseEntity.ok(Map.of("jobId", jobId));
    }

    @GetMapping("/stream/{jobId}")
    public SseEmitter stream(@PathVariable String jobId) {
        return sseEmitterService.createEmitter(jobId);
    }

    @PostMapping("/retry/{jobId}/{row}")
    public ResponseEntity<Void> retryRow(
            @PathVariable String jobId,
            @PathVariable int row) {
        emailAsyncService.retryRow(jobId, row);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/pause/{jobId}")
    public ResponseEntity<?> pauseJob(@PathVariable String jobId) {
        JobStatus job = jobStatusStore.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        job.setPaused(true);
        sseEmitterService.sendControl(jobId, "PAUSED");
        sseEmitterService.send(jobId, "message", "⏸ Job paused");
        return ResponseEntity.ok("PAUSED");
    }

    @PostMapping("/resume/{jobId}")
    public ResponseEntity<?> resumeJob(@PathVariable String jobId) {
        JobStatus job = jobStatusStore.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        job.setPaused(false);
        sseEmitterService.sendControl(jobId, "RESUMED");
        sseEmitterService.send(jobId, "message", "▶ Job resumed");
        return ResponseEntity.ok("RESUMED");
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<JobStatus> getStatus(@PathVariable String jobId) {
        JobStatus js = jobStatusStore.getJob(jobId);
        if (js == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(js);
    }

    @GetMapping("/report/{jobId}")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String jobId) {
        JobStatus job = jobStatusStore.getJob(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(("Job not found: " + jobId).getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Row,Email,Status\n");
        List<JobStatus.RowStatus> rows = job.getRowStatusList();
        for (JobStatus.RowStatus r : rows) {
            String email = r.getEmail() == null ? "" : r.getEmail().replace("\"", "\"\""); 
            String status = r.getStatus() == null ? "" : r.getStatus().replace("\"", "\"\""); 
            sb.append(r.getRow()).append(',').append('"').append(email).append('"').append(',').append('"').append(status).append('"').append('\n');
        }

        byte[] csvBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + jobId + ".csv");
        return ResponseEntity.ok().headers(headers).body(csvBytes);
    }

    @PostMapping("/preview")
    public List<Map<String, String>> previewExcel(@RequestParam("file") MultipartFile file) throws Exception {

        List<Map<String, String>> preview = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            // Read first 10 non-empty rows
            int count = 0;
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // skip header
                if (count >= 10) break;

                Cell emailCell = row.getCell(0);
                if (emailCell == null) continue;

                Map<String, String> rowData = new HashMap<>();
                rowData.put("row", String.valueOf(row.getRowNum()));
                rowData.put("email", emailCell.getStringCellValue());

                preview.add(rowData);
                count++;
            }
        }

        return preview;
    }

    @PostMapping("/retry-all/{jobId}")
    public ResponseEntity<?> retryAll(@PathVariable String jobId) {
        emailAsyncService.retryAllFailed(jobId);
        return ResponseEntity.ok("RETRY_ALL_STARTED");
    }

}
