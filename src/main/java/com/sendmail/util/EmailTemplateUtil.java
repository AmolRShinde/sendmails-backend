package com.sendmail.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class EmailTemplateUtil {

    public static String load(String file, Map<String, String> vars) throws Exception {
        InputStream is = EmailTemplateUtil.class
                .getClassLoader()
                .getResourceAsStream("templates/" + file);

        if (is == null) throw new RuntimeException("Template not found");

        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        // 🔥 REMOVE ALL LEADING / TRAILING WHITESPACE
        content = content.trim();

        for (var e : vars.entrySet()) {
            content = content.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return content;
    }
}
