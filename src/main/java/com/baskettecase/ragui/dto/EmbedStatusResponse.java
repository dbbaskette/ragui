package com.baskettecase.ragui.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTO representing the response format for the /embed-status endpoint.
 * Contains current status of all embedProc instances and summary statistics.
 */
public class EmbedStatusResponse {
    
    private List<EmbedProcMetrics> instances;
    private Summary summary;
    
    public EmbedStatusResponse() {}
    
    public EmbedStatusResponse(List<EmbedProcMetrics> instances, Summary summary) {
        this.instances = instances;
        this.summary = summary;
    }
    
    public List<EmbedProcMetrics> getInstances() { return instances; }
    public void setInstances(List<EmbedProcMetrics> instances) { this.instances = instances; }
    
    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }
    
    /**
     * Summary statistics for all embedProc instances
     */
    public static class Summary {
        private Integer totalInstances;
        private Integer activeInstances;
        private Integer totalProcessedChunks;
        private Integer totalErrorCount;
        private Double averageProcessingRate;
        private Instant lastRefresh;
        private Map<String, Integer> statusCounts;
        
        public Summary() {}
        
        public Summary(Integer totalInstances, Integer activeInstances, Integer totalProcessedChunks, 
                      Integer totalErrorCount, Double averageProcessingRate, Instant lastRefresh,
                      Map<String, Integer> statusCounts) {
            this.totalInstances = totalInstances;
            this.activeInstances = activeInstances;
            this.totalProcessedChunks = totalProcessedChunks;
            this.totalErrorCount = totalErrorCount;
            this.averageProcessingRate = averageProcessingRate;
            this.lastRefresh = lastRefresh;
            this.statusCounts = statusCounts;
        }
        
        // Getters and setters
        public Integer getTotalInstances() { return totalInstances; }
        public void setTotalInstances(Integer totalInstances) { this.totalInstances = totalInstances; }
        
        public Integer getActiveInstances() { return activeInstances; }
        public void setActiveInstances(Integer activeInstances) { this.activeInstances = activeInstances; }
        
        public Integer getTotalProcessedChunks() { return totalProcessedChunks; }
        public void setTotalProcessedChunks(Integer totalProcessedChunks) { this.totalProcessedChunks = totalProcessedChunks; }
        
        public Integer getTotalErrorCount() { return totalErrorCount; }
        public void setTotalErrorCount(Integer totalErrorCount) { this.totalErrorCount = totalErrorCount; }
        
        public Double getAverageProcessingRate() { return averageProcessingRate; }
        public void setAverageProcessingRate(Double averageProcessingRate) { this.averageProcessingRate = averageProcessingRate; }
        
        public Instant getLastRefresh() { return lastRefresh; }
        public void setLastRefresh(Instant lastRefresh) { this.lastRefresh = lastRefresh; }
        
        public Map<String, Integer> getStatusCounts() { return statusCounts; }
        public void setStatusCounts(Map<String, Integer> statusCounts) { this.statusCounts = statusCounts; }
    }
}