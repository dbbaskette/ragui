package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import com.baskettecase.ragui.model.Job;
import com.baskettecase.ragui.service.JobService;
import com.baskettecase.ragui.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class JobController {
    @Autowired
    private JobService jobService;
    @Autowired
    private RagService ragService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping("/job")
    public ResponseEntity<?> submitJob(@RequestBody ChatRequest request) {
        org.slf4j.LoggerFactory.getLogger(JobController.class).debug("/api/job received: {}", request);

        if (request.isRawRag()) {
            // Handle Raw RAG synchronously
            org.slf4j.LoggerFactory.getLogger(JobController.class).debug("Processing Raw RAG request synchronously.");
            try {
                ChatResponse chatResponse = ragService.chatRaw(request); // Changed to chatRaw for non-streaming Raw RAG
                return ResponseEntity.ok(chatResponse);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(JobController.class).error("Error processing Raw RAG request: {}", e.getMessage(), e);
                // Return a generic error response, or rethrow as appropriate
                ChatResponse errorResponse = new ChatResponse.Builder()
                    .answer("Error processing Raw RAG request: " + e.getMessage())
                    .source("ERROR")
                    .build();
                return ResponseEntity.status(500).body(errorResponse);
            }
        }

        // For streaming modes, proceed with async job creation
        Job job = jobService.createJob();
        // job.setStatus(Job.Status.QUEUED); // Status is set by Job constructor with an event
        // Async process
        executor.submit(() -> {
            job.setStatus(Job.Status.RUNNING);
            org.slf4j.LoggerFactory.getLogger(JobController.class).debug("Job {} started", job.getJobId());
            job.setStatus(Job.Status.RUNNING); // Initial status after queue
            job.addStatusEvent(Job.Status.RUNNING.name(), "Processing started", 5);
            org.slf4j.LoggerFactory.getLogger(JobController.class).debug("Job {} processing started", job.getJobId());
            try {
                RagService.RagStatusListener ragStatusListener = (statusMsg, progressVal) -> {
                    boolean isError = statusMsg.startsWith("LLM stream error:") || statusMsg.startsWith("Stream processing error:") || statusMsg.startsWith("Error during streaming:");
                    boolean isComplete = ("LLM stream complete".equals(statusMsg) || "COMPLETED".equals(statusMsg)) && progressVal == 100;

                    if (isComplete) {
                        job.setStatus(Job.Status.COMPLETED);
                        job.setStatusMessage(statusMsg);
                        job.setProgress(progressVal);
                        job.addStatusEvent(Job.Status.COMPLETED.name(), statusMsg, progressVal);
                        org.slf4j.LoggerFactory.getLogger(JobController.class).debug("Job {} stream completed, status set to COMPLETED by listener", job.getJobId());
                    } else if (isError) {
                        job.setError(statusMsg);
                        job.setStatus(Job.Status.FAILED);
                        job.setStatusMessage(statusMsg);
                        job.setProgress(progressVal); // Usually 100 for errors
                        job.addStatusEvent(Job.Status.FAILED.name(), statusMsg, progressVal);
                        org.slf4j.LoggerFactory.getLogger(JobController.class).error("Job {} stream failed via listener: {}", job.getJobId(), statusMsg);
                    } else {
                        // For intermediate RUNNING statuses
                        if (job.getStatus() != Job.Status.FAILED && job.getStatus() != Job.Status.COMPLETED) { // Don't override terminal states
                           job.setStatus(Job.Status.RUNNING);
                        }
                        job.setStatusMessage(statusMsg);
                        job.setProgress(progressVal);
                        job.addStatusEvent(Job.Status.RUNNING.name(), statusMsg, progressVal);
                    }
                    // Small delay to help SSE polling, consider if essential
                    // try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                };

                java.util.function.Consumer<String> chunkConsumer = chunk -> {
                    job.addStreamChunk(chunk);
                };

                ragService.chatStream(request, ragStatusListener, chunkConsumer);
                // chatStream is non-blocking (returns Flux). The actual job completion is signaled by ragStatusListener.
                // The executor thread for submitJob will complete after chatStream returns, 
                // but the job itself continues until the stream finishes or errors out.

            } catch (Exception e) {
                // This catch is for synchronous errors during chatStream setup.
                String errorMsg = "Failed to initiate stream: " + e.getMessage();
                job.setError(errorMsg);
                job.setStatus(Job.Status.FAILED);
                job.addStatusEvent(Job.Status.FAILED.name(), errorMsg, 100);
                org.slf4j.LoggerFactory.getLogger(JobController.class).error("Job {} failed to initiate stream: {}", job.getJobId(), e.getMessage(), e);
            }
            // jobService.updateJob(job); // Status updates and chunk additions are handled within Job methods using thread-safe collections.
                                        // Final job state (COMPLETED/FAILED) is set by the listener.
        });
        return ResponseEntity.ok(new JobIdResponse(job.getJobId()));
    }

    @GetMapping(value = "/events/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable String jobId) {
        org.slf4j.LoggerFactory.getLogger(JobController.class).debug("SSE connection opened for job {}", jobId);
        SseEmitter emitter = new SseEmitter(0L); // Use 0L for no timeout, or a specific timeout like 30_000L
        Job job = jobService.getJob(jobId);
        if (job == null) {
            try { 
                emitter.send(SseEmitter.event().name("error").data("{\"error\":\"Job not found: " + escape(jobId) + "\"}")); 
            } catch (IOException ignored) {}
            emitter.complete();
            org.slf4j.LoggerFactory.getLogger(JobController.class).warn("Job {} not found for SSE", jobId);
            return emitter;
        }

        final long[] lastStatusSeq = {0L};
        final long[] lastChunkSeq = {0L};

        // 1. Replay historical events. It's important to send them in order.
        // First, status events, then chunk events, maintaining their original sequence relative to each other if possible,
        // or at least replaying all of one type then all of another if Job model stores them separately.
        // For simplicity here, replaying all status then all chunks.

        // Replay status events
        for (Job.StatusEvent event : job.getAllEvents()) {
            try {
                StringBuilder json = new StringBuilder();
                json.append("{\"status\":\"").append(event.status).append("\"");
                if (event.statusMessage != null) {
                    json.append(",\"statusMessage\":\"").append(escape(event.statusMessage)).append("\"");
                }
                json.append(",\"progress\":").append(event.progress);
                json.append("}");
                emitter.send(SseEmitter.event().data(json.toString()));
                lastStatusSeq[0] = event.seq;
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger(JobController.class).warn("SSE status event replay failed for job {}: {}", jobId, e.getMessage());
                // If replay fails, client might miss some history. Consider if emitter should be completed.
            }
        }
        // Replay chunk events
        for (Job.StreamChunkEvent chunkEvent : job.getAllChunkEvents()) {
            try {
                emitter.send(SseEmitter.event().data(chunkEvent.chunk)); // Send raw chunk
                lastChunkSeq[0] = chunkEvent.seq;
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger(JobController.class).warn("SSE chunk event replay failed for job {}: {}", jobId, e.getMessage());
            }
        }

        // 2. Start background thread to stream new events and chunks
        executor.submit((Runnable) () -> { // Explicit cast to Runnable
            try {
                while (true) {
                    Job currentJobState = jobService.getJob(jobId); // Get fresh job state
                    if (currentJobState == null) {
                        org.slf4j.LoggerFactory.getLogger(JobController.class).warn("Job {} disappeared during SSE streaming.", jobId);
                        if (!emitter.toString().contains("completed")) emitter.completeWithError(new IllegalStateException("Job disappeared"));
                        break;
                    }

                    // Send new status events
                    var newStatusEvents = currentJobState.getEventsSince(lastStatusSeq[0]);
                    for (Job.StatusEvent event : newStatusEvents) {
                        try {
                            StringBuilder json = new StringBuilder();
                            json.append("{\"status\":\"").append(event.status).append("\"");
                            if (event.statusMessage != null) {
                                json.append(",\"statusMessage\":\"").append(escape(event.statusMessage)).append("\"");
                            }
                            json.append(",\"progress\":").append(event.progress);
                            json.append("}");
                            emitter.send(SseEmitter.event().data(json.toString()));
                            lastStatusSeq[0] = event.seq;
                        } catch (IOException e) {
                            org.slf4j.LoggerFactory.getLogger(JobController.class).warn("IOException sending status event for job {}: {}", jobId, e.getMessage());
                            // Optional: if (e.getMessage().contains("Broken pipe")) { emitter.complete(); break; }
                        }
                    }

                    // Send new chunk events
                    var newChunkEvents = currentJobState.getChunkEventsSince(lastChunkSeq[0]);
                    for (Job.StreamChunkEvent chunkEvent : newChunkEvents) {
                        try {
                            emitter.send(SseEmitter.event().data(chunkEvent.chunk)); // Send raw chunk
                            lastChunkSeq[0] = chunkEvent.seq;
                        } catch (IOException e) {
                             org.slf4j.LoggerFactory.getLogger(JobController.class).warn("IOException sending chunk event for job {}: {}", jobId, e.getMessage());
                             // Optional: if (e.getMessage().contains("Broken pipe")) { emitter.complete(); break; }
                        }
                    }

                    // Check job status for completion or failure
                    Job.Status currentStatus = currentJobState.getStatus();
                    if (currentStatus == Job.Status.COMPLETED || currentStatus == Job.Status.FAILED) {
                        String finalMessage = currentJobState.getStatusMessage();
                        if (currentStatus == Job.Status.COMPLETED && (finalMessage == null || finalMessage.trim().isEmpty())) finalMessage = "Job completed successfully";
                        if (currentStatus == Job.Status.FAILED && (finalMessage == null || finalMessage.trim().isEmpty())) finalMessage = currentJobState.getError() != null ? currentJobState.getError() : "Job processing failed";
                        
                        int finalProgress = currentJobState.getProgress();
                        if (finalProgress < 100 && (currentStatus == Job.Status.COMPLETED || currentStatus == Job.Status.FAILED)) {
                            finalProgress = 100;
                        }

                        StringBuilder finalJson = new StringBuilder();
                        finalJson.append("{\"status\":\"").append(currentStatus.name()).append("\"");
                        finalJson.append(",\"statusMessage\":\"").append(escape(finalMessage)).append("\"");
                        finalJson.append(",\"progress\":").append(finalProgress);
                        if (currentStatus == Job.Status.FAILED) {
                            finalJson.append(",\"error\":\"").append(escape(currentJobState.getError() != null ? currentJobState.getError() : "Unknown error")).append("\"");
                        }
                        finalJson.append("}");
                        
                        try {
                            if (!emitter.toString().contains("completed")) emitter.send(SseEmitter.event().data(finalJson.toString()));
                            org.slf4j.LoggerFactory.getLogger(JobController.class).debug("Sent final {} JSON status for job {}", currentStatus.name(), jobId);
                        } catch (IOException e) {
                            org.slf4j.LoggerFactory.getLogger(JobController.class).warn("Failed to send final {} JSON status for job {}: {}", currentStatus.name(), jobId, e.getMessage());
                        }
                        
                        if (!emitter.toString().contains("completed")) emitter.complete();
                        org.slf4j.LoggerFactory.getLogger(JobController.class).debug("SSE stream for job {} ended with status {}", jobId, currentStatus.name());
                        break; // Exit while loop
                    }
                    Thread.sleep(100); // Poll frequency
                } // end while
            } catch (InterruptedException e) {
                org.slf4j.LoggerFactory.getLogger(JobController.class).info("SSE polling thread interrupted for job {}", jobId);
                Thread.currentThread().interrupt(); // Preserve interrupt status
                if (!emitter.toString().contains("completed")) emitter.completeWithError(e);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(JobController.class).error("Exception in SSE polling loop for job {}: {}", jobId, e.getMessage(), e);
                try {
                    if (!emitter.toString().contains("completed")) emitter.send(SseEmitter.event().data("{\"error\":\"SSE streaming error: " + escape(e.getMessage()) + "\"}"));
                } catch (IOException ioe) {
                    org.slf4j.LoggerFactory.getLogger(JobController.class).warn("IOException while trying to send error event on SSE for job {}: {}", jobId, ioe.getMessage());
                }
                if (!emitter.toString().contains("completed")) emitter.completeWithError(e);
            }
        }); // end executor.submit lambda
        return emitter;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    public static class JobIdResponse {
        private String jobId;
        public JobIdResponse(String jobId) { this.jobId = jobId; }
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
    }
}
