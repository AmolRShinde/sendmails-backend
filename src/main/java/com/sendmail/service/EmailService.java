package com.sendmail.service;

import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

@Service
public class EmailService {

    @Value("${BREVO_API_KEY}")
    private String apiKey;

    @Value("${MAIL_FROM}")
    private String fromEmail;

    private static final String BREVO_URL =
            "https://api.brevo.com/v3/smtp/email";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(30))
            .build();

    /**
     * Sends a single email using Brevo HTTP API
     */
    public void sendMail(
            String to,
            String subject,
            String htmlBody,
            File attachment
    ) throws Exception {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("BREVO_API_KEY is missing");
        }

        if (fromEmail == null || fromEmail.isBlank()) {
            throw new IllegalStateException("MAIL_FROM is missing");
        }

        String attachmentJson = "";
        if (attachment != null && attachment.exists() && attachment.length() > 0) {
            String encoded = Base64.getEncoder()
                    .encodeToString(Files.readAllBytes(attachment.toPath()));

            attachmentJson = """
            ,"attachment":[
              {
                "content":"%s",
                "name":"%s"
              }
            ]
            """.formatted(encoded, attachment.getName());
        }

        String json = """
        {
          "sender": { "email": "%s" },
          "to": [ { "email": "%s" } ],
          "subject": "%s",
          "htmlContent": "%s"
          %s
        }
        """.formatted(
                fromEmail,
                to,
                escape(subject),
                escapeHtml(htmlBody),
                attachmentJson
        );

        Request request = new Request.Builder()
                .url(BREVO_URL)
                .post(RequestBody.create(
                        json,
                        MediaType.parse("application/json")
                ))
                .addHeader("api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                String body = response.body() != null
                        ? response.body().string()
                        : "no response body";

                throw new RuntimeException(
                        "Brevo API failed (" + response.code() + "): " + body
                );
            }
        }
    }

    // ---------------- HELPERS ----------------

    private String escape(String s) {
        return s.replace("\"", "\\\"");
    }

    private String escapeHtml(String s) {
        return escape(s.replace("\n", "<br>"));
    }
}
