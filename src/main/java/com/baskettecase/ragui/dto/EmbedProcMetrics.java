package com.baskettecase.ragui.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * DTO representing embedProc metrics received from RabbitMQ queue.
 * Maps to the JSON format published by embedProc instances.
 */
public class EmbedProcMetrics {
    
    @JsonProperty("instanceId")
    private String instanceId;
    
    @JsonProperty("timestamp")
    private String timestamp;
    
    @JsonProperty("totalChunks")
    private Integer totalChunks;
    
    @JsonProperty("processedChunks")
    private Integer processedChunks;
    
    @JsonProperty("errorCount")
    private Integer errorCount;
    
    @JsonProperty("processingRate")
    private Double processingRate;
    
    @JsonProperty("uptime")
    private String uptime;
    
    @JsonProperty("status")
    private String status;
    
    // Timestamp when this metric was received by ragui
    private Instant lastUpdated;
    
    // Default constructor for Jackson
    public EmbedProcMetrics() {
        this.lastUpdated = Instant.now();
    }
    
    // Constructor with all fields
    public EmbedProcMetrics(String instanceId, String timestamp, Integer totalChunks, 
                           Integer processedChunks, Integer errorCount, Double processingRate, 
                           String uptime, String status) {
        this.instanceId = instanceId;
        this.timestamp = timestamp;
        this.totalChunks = totalChunks;
        this.processedChunks = processedChunks;
        this.errorCount = errorCount;
        this.processingRate = processingRate;
        this.uptime = uptime;
        this.status = status;
        this.lastUpdated = Instant.now();
    }
    
    // Getters and setters
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    
    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    
    public Integer getProcessedChunks() { return processedChunks; }
    public void setProcessedChunks(Integer processedChunks) { this.processedChunks = processedChunks; }
    
    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }
    
    public Double getProcessingRate() { return processingRate; }
    public void setProcessingRate(Double processingRate) { this.processingRate = processingRate; }
    
    public String getUptime() { return uptime; }
    public void setUptime(String uptime) { this.uptime = uptime; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
    
    // Calculated property for progress percentage
    public Double getProgressPercentage() {
        if (totalChunks == null || totalChunks == 0 || processedChunks == null) {
            return 0.0;
        }
        return (processedChunks.doubleValue() / totalChunks.doubleValue()) * 100.0;
    }
    
    @Override
    public String toString() {
        return "EmbedProcMetrics{" +
                "instanceId='" + instanceId + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", totalChunks=" + totalChunks +
                ", processedChunks=" + processedChunks +
                ", errorCount=" + errorCount +
                ", processingRate=" + processingRate +
                ", uptime='" + uptime + '\'' +
                ", status='" + status + '\'' +
                ", lastUpdated=" + lastUpdated +
                '}';
    }
}