package com.sendmail;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public class AttachmentDownloader {

    /**
     * Supports Google Drive links:
     * 1. https://drive.google.com/file/d/<ID>/view
     * 2. https://drive.google.com/open?id=<ID>
     * 3. https://drive.google.com/uc?id=<ID>
     */
    public static File download(String driveLink) throws Exception {

        if (driveLink == null || driveLink.isBlank()) {
            return null;
        }

        String fileId = extractFileId(driveLink);
        if (fileId == null) {
            return null;
        }

        String downloadUrl =
                "https://drive.google.com/uc?export=download&id=" + fileId;

        HttpURLConnection conn =
                (HttpURLConnection) new URL(downloadUrl).openConnection();

        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException(
                    "Failed to download attachment, HTTP " + responseCode);
        }

        String filename = "attachment-" + UUID.randomUUID();

        File tempFile = File.createTempFile(filename, ".bin");

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tempFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        return tempFile;
    }

    // ---------------- HELPERS ----------------

    private static String extractFileId(String link) {

        // /file/d/<ID>/
        if (link.contains("/file/d/")) {
            int start = link.indexOf("/file/d/") + 8;
            int end = link.indexOf("/", start);
            return end > start ? link.substring(start, end) : null;
        }

        // ?id=<ID>
        if (link.contains("id=")) {
            return link.substring(link.indexOf("id=") + 3).split("&")[0];
        }

        return null;
    }
}
