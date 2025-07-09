package com.baskettecase.ragui.service;

import com.baskettecase.ragui.dto.EmbedProcMetrics;
import com.baskettecase.ragui.dto.EmbedStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Map;

/**
 * Service for monitoring embedProc instances via RabbitMQ.
 * Consumes metrics messages and maintains a cache of current instance statuses.
 */
@Service
@ConditionalOnProperty(
    value = "app.monitoring.rabbitmq.enabled", 
    havingValue = "true", 
    matchIfMissing = true
)
public class EmbedProcMonitoringService {
    
    private static final Logger logger = LoggerFactory.getLogger(EmbedProcMonitoringService.class);
    
    @Value("${app.monitoring.rabbitmq.cache-duration-minutes:30}")
    private int cacheDurationMinutes;
    
    // Thread-safe cache of instance metrics, keyed by instanceId
    private final Map<String, EmbedProcMetrics> instanceMetricsCache = new ConcurrentHashMap<>();
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * RabbitMQ listener for embedProc metrics messages.
     * Automatically consumes messages from the configured queue.
     */
    @RabbitListener(queues = "${app.monitoring.rabbitmq.queue-name:embedproc.metrics}")
    public void handleEmbedProcMetrics(org.springframework.amqp.core.Message message) {
        try {
            logger.debug("Received embedProc metrics message: {}", new String(message.getBody()));
            
            // Handle SCDF message format - extract payload from Spring Cloud Stream envelope
            String payload = extractPayloadFromSCDFMessage(message);
            logger.debug("Extracted payload: {}", payload);
            
            // Parse the JSON message into EmbedProcMetrics
            EmbedProcMetrics metrics = objectMapper.readValue(payload, EmbedProcMetrics.class);
            
            // Update the cache with latest metrics
            instanceMetricsCache.put(metrics.getInstanceId(), metrics);
            
            logger.info("Updated metrics for instance '{}': status={}, processed={}/{}, rate={}/s", 
                       metrics.getInstanceId(), 
                       metrics.getStatus(),
                       metrics.getProcessedChunks(), 
                       metrics.getTotalChunks(),
                       metrics.getProcessingRate());
            
        } catch (Exception e) {
            logger.error("Failed to process embedProc metrics message: {}", 
                        message != null ? new String(message.getBody()) : "null", e);
        }
    }
    
    /**
     * Extract the actual payload from a Spring Cloud Data Flow message.
     * SCDF messages are typically wrapped in a Spring Cloud Stream envelope.
     */
    private String extractPayloadFromSCDFMessage(org.springframework.amqp.core.Message message) {
        String rawMessage = new String(message.getBody());
        logger.debug("Raw message: {}", rawMessage);
        
        try {
            // Try to parse as SCDF envelope first
            if (rawMessage.contains("\"payload\"")) {
                // This is likely a Spring Cloud Stream envelope
                Map<String, Object> envelope = objectMapper.readValue(rawMessage, Map.class);
                Object payload = envelope.get("payload");
                
                if (payload instanceof String) {
                    return (String) payload;
                } else if (payload instanceof Map) {
                    // If payload is already an object, convert it back to JSON
                    return objectMapper.writeValueAsString(payload);
                }
            }
            
            // If no envelope detected, assume it's raw JSON
            return rawMessage;
            
        } catch (Exception e) {
            logger.error("Failed to extract payload from SCDF message: {}", rawMessage, e);
            // Fallback to raw message
            return rawMessage;
        }
    }
    
    /**
     * Get current status of all embedProc instances.
     * Removes stale entries older than the configured cache duration.
     */
    public EmbedStatusResponse getCurrentStatus() {
        // Clean up stale entries first
        cleanupStaleEntries();
        
        // Get current instances
        List<EmbedProcMetrics> currentInstances = new ArrayList<>(instanceMetricsCache.values());
        
        // Generate summary statistics
        EmbedStatusResponse.Summary summary = generateSummary(currentInstances);
        
        logger.debug("Returning status for {} embedProc instances", currentInstances.size());
        
        return new EmbedStatusResponse(currentInstances, summary);
    }
    
    /**
     * Get status of a specific embedProc instance by ID.
     */
    public Optional<EmbedProcMetrics> getInstanceStatus(String instanceId) {
        cleanupStaleEntries();
        return Optional.ofNullable(instanceMetricsCache.get(instanceId));
    }
    
    /**
     * Get list of all known instance IDs (including stale ones).
     */
    public Set<String> getAllInstanceIds() {
        return new HashSet<>(instanceMetricsCache.keySet());
    }
    
    /**
     * Remove metrics entries that are older than the configured cache duration.
     */
    private void cleanupStaleEntries() {
        Instant cutoff = Instant.now().minus(cacheDurationMinutes, ChronoUnit.MINUTES);
        
        List<String> staleInstances = instanceMetricsCache.entrySet().stream()
            .filter(entry -> entry.getValue().getLastUpdated().isBefore(cutoff))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        if (!staleInstances.isEmpty()) {
            logger.info("Removing {} stale embedProc instances from cache: {}", 
                       staleInstances.size(), staleInstances);
            staleInstances.forEach(instanceMetricsCache::remove);
        }
    }
    
    /**
     * Generate summary statistics from current instances.
     */
    private EmbedStatusResponse.Summary generateSummary(List<EmbedProcMetrics> instances) {
        if (instances.isEmpty()) {
            return new EmbedStatusResponse.Summary(0, 0, 0, 0, 0.0, Instant.now(), new HashMap<>());
        }
        
        // Calculate summary statistics
        int totalInstances = instances.size();
        
        // Count active instances (not in ERROR or FAILED status)
        int activeInstances = (int) instances.stream()
            .filter(m -> m.getStatus() != null && 
                        !m.getStatus().equalsIgnoreCase("ERROR") && 
                        !m.getStatus().equalsIgnoreCase("FAILED"))
            .count();
        
        // Sum processed chunks and errors
        int totalProcessedChunks = instances.stream()
            .mapToInt(m -> m.getProcessedChunks() != null ? m.getProcessedChunks() : 0)
            .sum();
        
        int totalErrorCount = instances.stream()
            .mapToInt(m -> m.getErrorCount() != null ? m.getErrorCount() : 0)
            .sum();
        
        // Calculate average processing rate
        double averageProcessingRate = instances.stream()
            .filter(m -> m.getProcessingRate() != null)
            .mapToDouble(EmbedProcMetrics::getProcessingRate)
            .average()
            .orElse(0.0);
        
        // Count instances by status
        Map<String, Integer> statusCounts = instances.stream()
            .filter(m -> m.getStatus() != null)
            .collect(Collectors.groupingBy(
                EmbedProcMetrics::getStatus,
                Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)
            ));
        
        return new EmbedStatusResponse.Summary(
            totalInstances,
            activeInstances,
            totalProcessedChunks,
            totalErrorCount,
            Math.round(averageProcessingRate * 100.0) / 100.0, // Round to 2 decimal places
            Instant.now(),
            statusCounts
        );
    }
    
    /**
     * Get cache statistics for debugging/monitoring.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", instanceMetricsCache.size());
        stats.put("cacheDurationMinutes", cacheDurationMinutes);
        stats.put("allInstanceIds", getAllInstanceIds());
        return stats;
    }
    
    /**
     * Clear all cached metrics (for testing or manual refresh).
     */
    public void clearCache() {
        logger.info("Clearing embedProc metrics cache");
        instanceMetricsCache.clear();
    }
}