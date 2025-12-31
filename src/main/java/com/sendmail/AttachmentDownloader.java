package com.sendmail;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.File;

public class AttachmentDownloader {

    public static File download(String urlPath) throws Exception {
        if (urlPath == null) return null;
        if (!urlPath.startsWith("http")) {
            File f = new File(urlPath);
            return f.exists() ? f : null;
        }

        String downloadUrl = urlPath;
        if (urlPath.contains("drive.google.com/file/d/") && urlPath.contains("/view")) {
            String id = urlPath.split("/file/d/")[1].split("/")[0];
            downloadUrl = "https://drive.google.com/uc?export=download&id=" + id;
        }

        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("Attachment download failed: " + conn.getResponseCode());
        }

        String disposition = conn.getHeaderField("Content-Disposition");
         String fileName = "attachment.pdf";
        if (disposition != null && disposition.contains("filename=")) {
            fileName = disposition.split("filename=")[1].replaceAll("\"", "").trim();
        } else {
            String[] parts = url.getPath().split("/");
            fileName = parts[parts.length - 1];
        }
        if (fileName == null || fileName.isEmpty()) fileName = "attachment";

        Path temp = Files.createTempFile("att_", "_" + fileName);
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }

        if (Files.size(temp) < 100) {
          throw new RuntimeException("Downloaded file too small, possibly HTML page instead of PDF");
        }

        return temp.toFile();
    }
}
