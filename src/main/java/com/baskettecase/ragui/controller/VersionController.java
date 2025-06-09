package com.baskettecase.ragui.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for exposing application version information.
 *
 * Endpoint:
 *   - GET /api/version: Returns current app version string.
 */
@RestController
@RequestMapping("/api")
public class VersionController {
    /**
     * Returns the current application version.
     * @return Map containing the version string
     */
    @GetMapping("/version")
    public Map<String, String> getVersion() {
        Map<String, String> version = new HashMap<>();
        version.put("version", "0.0.101"); // Update if you want to fetch dynamically
        return version;
    }
}
