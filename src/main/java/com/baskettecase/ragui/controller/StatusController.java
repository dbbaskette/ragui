package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.service.AppStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for exposing application status for health checks or UI display.
 *
 * Endpoint:
 *   - GET /api/status: Returns current application status string.
 */
@RestController
@RequestMapping("/api")
public class StatusController {
    @Autowired
    private AppStatus appStatus;

    /**
     * Returns the current application status.
     * @return Map containing the status string
     */
    @GetMapping("/status")
    public Map<String, String> getStatus() {
        Map<String, String> status = new HashMap<>();
        status.put("status", appStatus.getStatus());
        return status;
    }
}
