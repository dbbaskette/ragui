package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.dto.EmbedProcMetrics;
import com.baskettecase.ragui.dto.EmbedStatusResponse;
import com.baskettecase.ragui.service.EmbedProcMonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for embedProc monitoring endpoints.
 * Provides access to real-time status of embedProc instances.
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(
    value = "app.monitoring.rabbitmq.enabled", 
    havingValue = "true", 
    matchIfMissing = true
)
public class EmbedStatusController {
    
    private static final Logger logger = LoggerFactory.getLogger(EmbedStatusController.class);
    
    @Autowired
    private EmbedProcMonitoringService monitoringService;
    
    /**
     * Main endpoint to get current status of all embedProc instances.
     * Returns real-time metrics from RabbitMQ stream.
     */
    @GetMapping("/embed-status")
    public ResponseEntity<EmbedStatusResponse> getEmbedStatus() {
        logger.debug("GET /embed-status - Retrieving current embedProc status");
        
        try {
            EmbedStatusResponse status = monitoringService.getCurrentStatus();
            logger.info("Returning embedProc status: {} instances", 
                       status.getInstances().size());
            return ResponseEntity.ok(status);
            
        } catch (Exception e) {
            logger.error("Error retrieving embedProc status", e);
            return ResponseEntity.status(500).body(
                new EmbedStatusResponse(
                    java.util.Collections.emptyList(),
                    new EmbedStatusResponse.Summary(0, 0, 0, 0, 0.0, 
                                                   java.time.Instant.now(), 
                                                   new HashMap<>())
                )
            );
        }
    }
    
    /**
     * Get status of a specific embedProc instance by ID.
     */
    @GetMapping("/embed-status/{instanceId}")
    public ResponseEntity<EmbedProcMetrics> getInstanceStatus(@PathVariable String instanceId) {
        logger.debug("GET /embed-status/{} - Retrieving specific instance status", instanceId);
        
        Optional<EmbedProcMetrics> metrics = monitoringService.getInstanceStatus(instanceId);
        
        if (metrics.isPresent()) {
            logger.info("Found metrics for instance: {}", instanceId);
            return ResponseEntity.ok(metrics.get());
        } else {
            logger.warn("No metrics found for instance: {}", instanceId);
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * Debug endpoint to get cache statistics and configuration.
     */
    @GetMapping("/embed-status/debug/cache")
    public ResponseEntity<Map<String, Object>> getCacheDebugInfo() {
        logger.debug("GET /embed-status/debug/cache - Retrieving cache debug info");
        
        try {
            Map<String, Object> debugInfo = monitoringService.getCacheStats();
            return ResponseEntity.ok(debugInfo);
            
        } catch (Exception e) {
            logger.error("Error retrieving cache debug info", e);
            Map<String, Object> errorInfo = new HashMap<>();
            errorInfo.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorInfo);
        }
    }
    
    /**
     * Administrative endpoint to clear the metrics cache.
     * Useful for testing or forcing a refresh.
     */
    @PostMapping("/embed-status/debug/clear-cache")
    public ResponseEntity<Map<String, String>> clearCache() {
        logger.info("POST /embed-status/debug/clear-cache - Clearing metrics cache");
        
        try {
            monitoringService.clearCache();
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "Cache cleared successfully");
            response.put("timestamp", java.time.Instant.now().toString());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error clearing cache", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    /**
     * Health check endpoint specifically for embedProc monitoring.
     */
    @GetMapping("/embed-status/health")
    public ResponseEntity<Map<String, Object>> getMonitoringHealth() {
        logger.debug("GET /embed-status/health - Checking monitoring health");
        
        Map<String, Object> health = new HashMap<>();
        
        try {
            Map<String, Object> cacheStats = monitoringService.getCacheStats();
            EmbedStatusResponse status = monitoringService.getCurrentStatus();
            
            health.put("status", "UP");
            health.put("cacheSize", cacheStats.get("cacheSize"));
            health.put("activeInstances", status.getSummary().getActiveInstances());
            health.put("totalInstances", status.getSummary().getTotalInstances());
            health.put("lastCheck", java.time.Instant.now().toString());
            
            return ResponseEntity.ok(health);
            
        } catch (Exception e) {
            logger.error("Monitoring health check failed", e);
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
            health.put("lastCheck", java.time.Instant.now().toString());
            
            return ResponseEntity.status(503).body(health);
        }
    }
}