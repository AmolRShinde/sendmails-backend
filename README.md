Ready-to-run Spring Boot backend with SSE support for async email sending.

How to run:
1. Unzip the project.
2. Set environment variables for EMAIL_USER and EMAIL_PASS (or edit application.properties).
   Example (Windows CMD):
     set EMAIL_USER=your@gmail.com
     set EMAIL_PASS=your_app_password
   Linux / macOS:
     export EMAIL_USER=your@gmail.com
     export EMAIL_PASS=your_app_password
3. Build & run:
     mvn clean package
     mvn spring-boot:run
Endpoints:
 - POST /api/email/send-async  (form multipart file -> returns {jobId})
 - GET  /api/email/stream/{jobId}  (SSE stream)
 - GET  /api/email/status/{jobId}  (current job status JSON)
 - GET  /api/email/report/{jobId}  (CSV report)
