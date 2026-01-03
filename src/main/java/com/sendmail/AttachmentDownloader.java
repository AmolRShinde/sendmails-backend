package com.sendmail;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public class AttachmentDownloader {

    // Map content-type → extension
    private static final Map<String, String> EXT_MAP = Map.ofEntries(
            Map.entry("application/pdf", ".pdf"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx")
    );

    public static File download(String driveLink) throws Exception {

        if (driveLink == null || driveLink.isBlank()) {
            return null;
        }

        // Normalize Google Drive link → direct download
        String fileId = extractFileId(driveLink);
        if (fileId == null) {
            return null;
        }

        String downloadUrl =
                "https://drive.google.com/uc?export=download&id=" + fileId;

        HttpURLConnection conn =
                (HttpURLConnection) new URL(downloadUrl).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.connect();

        String contentType = conn.getContentType();
        String extension = EXT_MAP.getOrDefault(contentType, ".pdf"); // safe fallback

        File temp = File.createTempFile("JanToMar2026_MaintenancePayment_Bill-", extension);

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        return temp;
    }

    private static String extractFileId(String link) {

        // Format: /file/d/<ID>/view
        if (link.contains("/file/d/")) {
            int start = link.indexOf("/d/") + 3;
            int end = link.indexOf("/", start);
            return end > start ? link.substring(start, end) : null;
        }

        // Format: ?id=<ID>
        if (link.contains("id=")) {
            return link.substring(link.indexOf("id=") + 3);
        }

        return null;
    }
}
