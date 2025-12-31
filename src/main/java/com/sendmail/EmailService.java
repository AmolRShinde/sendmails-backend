package com.sendmail;

import org.springframework.stereotype.Service;

@Service
public class EmailService {

    // Just a placeholder for sending an email
    public void sendSimpleEmail(String email) throws Exception {
        // implement actual SMTP logic here
        System.out.println("Sending email to " + email);
    }
}
