package com.sendmail.job;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.stream.Collectors;

public class JobStatus {

    private final String jobId;
    private volatile int progress;
    private volatile boolean paused = false;

    private final Map<Integer, RowStatus> rows = new ConcurrentHashMap<>();

    public JobStatus(String jobId) {
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * Add a new row or update an existing row safely
     */
    public void addOrUpdateRow(
            int row,
            String email,
            String name,
            String driveLink,
            String status
    ) {
        rows.compute(row, (k, existing) -> {
            if (existing == null) {
                return new RowStatus(row, email, name, driveLink, status);
            }
            existing.setStatus(status);
            return existing;
        });
    }

    public RowStatus getRow(int row) {
        return rows.get(row);
    }

    public List<RowStatus> getRowStatusList() {
        return getAllRowsNewestFirst();
    }

    public List<RowStatus> getAllRowsNewestFirst() {
        return rows.values()
                .stream()
                .sorted(Comparator.comparingInt(RowStatus::getRow).reversed())
                .collect(Collectors.toList());
    }

    // ================== Inner Class ==================

    public static class RowStatus {
        private final int row;
        private final String email;
        private final String name;
        private final String driveLink;
        private volatile String status;

        public RowStatus(
                int row,
                String email,
                String name,
                String driveLink,
                String status
        ) {
            this.row = row;
            this.email = email;
            this.name = name;
            this.driveLink = driveLink;
            this.status = status;
        }

        public int getRow() {
            return row;
        }

        public String getEmail() {
            return email;
        }

        public String getName() {
            return name;
        }

        public String getDriveLink() {
            return driveLink;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
