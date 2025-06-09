package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.model.Job;
import com.baskettecase.ragui.service.JobService;
import com.baskettecase.ragui.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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
    public JobIdResponse submitJob(@RequestBody ChatRequest request) {
        org.slf4j.LoggerFactory.getLogger(JobController.class).debug("/api/job received: {}", request);
        Job job = jobService.createJob();
        job.setStatus(Job.Status.QUEUED);
        // Async process
        executor.submit(() -> {
            job.setStatus(Job.Status.RUNNING);
            org.slf4j.LoggerFactory.getLogger(JobController.class).debug("Job {} started", job.getJobId());
            try {
                StringBuilder statusMsg = new StringBuilder();
                var response = ragService.chat(request, (status, progress) -> {
                    job.setStatus(Job.Status.RUNNING);
                    statusMsg.append(status).append("\n");
                });
                job.setResult(response.getAnswer());
                job.setStatus(Job.Status.COMPLETED);
                org.slf4j.LoggerFactory.getLogger(JobController.class).debug("Job {} completed", job.getJobId());
            } catch (Exception e) {
                job.setError(e.getMessage());
                job.setStatus(Job.Status.FAILED);
                org.slf4j.LoggerFactory.getLogger(JobController.class).error("Job {} failed: {}", job.getJobId(), e.getMessage(), e);
            }
            jobService.updateJob(job);
        });
        return new JobIdResponse(job.getJobId());
    }

    @GetMapping(value = "/events/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable String jobId) {
        org.slf4j.LoggerFactory.getLogger(JobController.class).debug("SSE connection opened for job {}", jobId);
        SseEmitter emitter = new SseEmitter(0L);
        Job job = jobService.getJob(jobId);
        if (job == null) {
            try { emitter.send(SseEmitter.event().data("{\"error\":\"Job not found\"}")); } catch (IOException ignored) {}
            emitter.complete();
            org.slf4j.LoggerFactory.getLogger(JobController.class).warn("Job {} not found for SSE", jobId);
            return emitter;
        }
        executor.submit(() -> {
            Job.Status lastStatus = null;
            try {
                while (true) {
                    Job.Status status = job.getStatus();
                    if (status != lastStatus) {
                        emitter.send(SseEmitter.event().data("{\"status\":\"" + status + "\"}"));
                        lastStatus = status;
                    }
                    if (status == Job.Status.COMPLETED) {
                        emitter.send(SseEmitter.event().data("{\"response\":{\"text\":\"" + escape(job.getResult()) + "\"}}"));
                        emitter.complete();
                        org.slf4j.LoggerFactory.getLogger(JobController.class).debug("SSE completed for job {}", jobId);
                        break;
                    } else if (status == Job.Status.FAILED) {
                        emitter.send(SseEmitter.event().data("{\"error\":\"" + escape(job.getError()) + "\"}"));
                        emitter.complete();
                        org.slf4j.LoggerFactory.getLogger(JobController.class).debug("SSE failed for job {}", jobId);
                        break;
                    }
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().data("{\"error\":\"" + escape(e.getMessage()) + "\"}")); } catch (IOException ignored) {}
                emitter.complete();
                org.slf4j.LoggerFactory.getLogger(JobController.class).error("SSE error for job {}: {}", jobId, e.getMessage(), e);
            }
        });
        return emitter;
    }

    public static class JobIdResponse {
        private String jobId;
        public JobIdResponse(String jobId) { this.jobId = jobId; }
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
