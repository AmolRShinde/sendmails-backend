package com.sendmail.job;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.io.File;

@Component
public class JobStatusStore {

    private final Map<String, JobStatus> store = new ConcurrentHashMap<>();

    private final Map<String, File> excelFiles = new ConcurrentHashMap<>();

    public void registerExcel(String jobId, File file) {
        excelFiles.put(jobId, file);
    }

    public File getExcel(String jobId) {
        return excelFiles.get(jobId);
    }

    public JobStatus createJob(String jobId) {
        JobStatus js = new JobStatus(jobId);
        store.put(jobId, js);
        return js;
    }

    public JobStatus getJob(String jobId) {
        return store.get(jobId);
    }

    public void addOrUpdateRow(
            String jobId,
            int row,
            String email,
            String name,
            String driveLink,
            String status
    ) {
        JobStatus js = store.get(jobId);
        if (js != null) {
            js.addOrUpdateRow(row, email, name, driveLink, status);
        }
    }

    /** ✅ ADD THIS METHOD */
    public void updateRowStatus(String jobId, int row, String status) {
        JobStatus js = store.get(jobId);
        if (js != null) {
            JobStatus.RowStatus rs = js.getRow(row);
            if (rs != null) {
                rs.setStatus(status);
            }
        }
    }

    public JobStatus.RowStatus getRow(String jobId, int row) {
        JobStatus js = store.get(jobId);
        return js != null ? js.getRow(row) : null;
    }

    public void setProgress(String jobId, int progress) {
        JobStatus js = store.get(jobId);
        if (js != null) {
            js.setProgress(progress);
        }
    }
}
