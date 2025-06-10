package com.baskettecase.ragui.model;

import java.util.concurrent.atomic.AtomicReference;

public class Job {
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }
    private final String jobId;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.QUEUED);
    private volatile String result;
    private volatile String error;
    private volatile String statusMessage;
    private volatile int progress;

    public Job(String jobId) {
        this.jobId = jobId;
        this.statusMessage = null;
        this.progress = 0;
    }
    public String getJobId() { return jobId; }
    public Status getStatus() { return status.get(); }
    public void setStatus(Status status) { this.status.set(status); }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
}
