package com.baskettecase.ragui.model;

import java.util.concurrent.atomic.AtomicReference;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Job {
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }
    private final String jobId;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.QUEUED);
    private volatile String result;
    private volatile String error;
    private volatile String statusMessage;
    private volatile int progress;

    // FIFO event buffer for status events
    public static class StatusEvent {
        public final long seq;
        public final String status;
        public final String statusMessage;
        public final int progress;
        public final long timestamp;
        public StatusEvent(long seq, String status, String statusMessage, int progress, long timestamp) {
            this.seq = seq;
            this.status = status;
            this.statusMessage = statusMessage;
            this.progress = progress;
            this.timestamp = timestamp;
        }
    }
    private final List<StatusEvent> eventBuffer = new CopyOnWriteArrayList<>();
    private volatile long eventSeq = 0;

    // FIFO event buffer for stream chunks
    public static class StreamChunkEvent {
        public final long seq;
        public final String chunk;
        public final long timestamp;
        public StreamChunkEvent(long seq, String chunk, long timestamp) {
            this.seq = seq;
            this.chunk = chunk;
            this.timestamp = timestamp;
        }
    }
    private final List<StreamChunkEvent> chunkBuffer = new CopyOnWriteArrayList<>();
    private volatile long chunkEventSeq = 0;

    public void addStatusEvent(String status, String statusMessage, int progress) {
        eventBuffer.add(new StatusEvent(++eventSeq, status, statusMessage, progress, System.currentTimeMillis()));
    }
    public List<StatusEvent> getAllEvents() {
        return eventBuffer;
    }
    public List<StatusEvent> getEventsSince(long lastSeq) {
        return eventBuffer.stream().filter(e -> e.seq > lastSeq).toList();
    }

    public void addStreamChunk(String chunk) {
        chunkBuffer.add(new StreamChunkEvent(++chunkEventSeq, chunk, System.currentTimeMillis()));
    }
    public List<StreamChunkEvent> getAllChunkEvents() {
        return chunkBuffer;
    }
    public List<StreamChunkEvent> getChunkEventsSince(long lastChunkSeq) {
        return chunkBuffer.stream().filter(c -> c.seq > lastChunkSeq).toList();
    }

    public Job(String jobId) {
        this.jobId = jobId;
        this.statusMessage = null;
        this.progress = 0;
        // Add initial status event
        addStatusEvent(Status.QUEUED.name(), null, 0);
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
