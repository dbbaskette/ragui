package com.baskettecase.ragui.service;

import com.baskettecase.ragui.model.Job;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing chat jobs in memory.
 * Uses a thread-safe ConcurrentHashMap for job storage.
 */
@Service
public class JobService {
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /**
     * Creates a new Job with a unique ID and stores it.
     * @return The new Job
     */
    public Job createJob() {
        String jobId = UUID.randomUUID().toString();
        Job job = new Job(jobId);
        jobs.put(jobId, job);
        return job;
    }

    /**
     * Retrieves a Job by its ID.
     * @param jobId The job ID
     * @return The Job, or null if not found
     */
    public Job getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Updates an existing Job in the map.
     * @param job The Job to update
     */
    public void updateJob(Job job) {
        jobs.put(job.getJobId(), job);
    }
}
