package com.sendmail;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import com.sendmail.JobStatus.RowStatus;

@Component
public class JobStatusStore {

    private final Map<String, JobStatus> store = new HashMap<>();

    public synchronized RowStatus getRow(String jobId, int row) {
        return store.get(jobId).getRow(row);
    }

    public void createJob(String jobId) {
        store.put(jobId, new JobStatus(jobId));
    }

    public void setProgress(String jobId, int progress) {
        JobStatus js = store.get(jobId);
        if (js != null) js.setProgress(progress);
    }

    public void setRowStatus(String jobId, int row, String email, String status) {
        JobStatus js = store.get(jobId);
        if (js != null) js.addRowStatus(row, email, status);
    }

    public JobStatus getJob(String jobId) {
        return store.get(jobId);
    }
}
