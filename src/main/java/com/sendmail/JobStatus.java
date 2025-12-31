package com.sendmail;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

public class JobStatus {
    private String jobId;
    private int progress;
    private List<RowStatus> rowStatusList = new ArrayList<>();
    private volatile boolean paused = false;

    private final Map<Integer, RowStatus> rows = new ConcurrentHashMap<>();

    public boolean isPaused() { return paused; }
    public void setPaused(boolean p) { paused = p; }

    public JobStatus(String jobId) {
        this.jobId = jobId;
        this.progress = 0;
    }

    public void setProgress(int progress) { this.progress = progress; }
    public void addRowStatus(int row, String email, String status) {
        rowStatusList.add(new RowStatus(row, email, status));
    }
    public String getJobId() { return jobId; }
    public int getProgress() { return progress; }
    public List<RowStatus> getRowStatusList() {
        List<RowStatus> list = new ArrayList<>(rows.values());
        list.sort(Comparator.comparingInt(RowStatus::getRow).reversed());
        return list;
    }
    public RowStatus getRow(int row) {
      return rows.get(row);
    }

    public void addOrUpdateRow(int row, String email, String status) {
        rows.put(row, new RowStatus(row, email, status));
    }

    public List<RowStatus> getAllRowsNewestFirst() {
        return rows.values()
                .stream()
                .sorted(Comparator.comparingInt(RowStatus::getRow).reversed())
                .collect(Collectors.toList());
    }

    public static class RowStatus {
        private int row;
        private String email;
        private String status;
        
        public RowStatus(int row, String email, String status) {
            this.row = row; this.email = email; this.status = status;
        }
        public int getRow() { return row; }
        public String getEmail() { return email; }
        public String getStatus() { return status; }
        public void setStatus(String status) {
            this.status = status;
        }
    }

}
