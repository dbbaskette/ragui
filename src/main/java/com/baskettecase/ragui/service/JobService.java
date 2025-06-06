package com.baskettecase.ragui.service;

import com.baskettecase.ragui.model.Job;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobService {
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public Job createJob() {
        String jobId = UUID.randomUUID().toString();
        Job job = new Job(jobId);
        jobs.put(jobId, job);
        return job;
    }

    public Job getJob(String jobId) {
        return jobs.get(jobId);
    }

    public void updateJob(Job job) {
        jobs.put(job.getJobId(), job);
    }
}
