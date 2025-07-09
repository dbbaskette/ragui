package com.baskettecase.ragui.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

@RestController
@RequestMapping("/api/query-expansion")
public class QueryExpansionController {
    private final AtomicBoolean enabled;

    public QueryExpansionController(@Value("${ragui.query.expansion.enabled:true}") boolean initialValue) {
        this.enabled = new AtomicBoolean(initialValue);
    }

    @GetMapping
    public Map<String, Boolean> getState() {
        return Map.of("enabled", enabled.get());
    }

    @PostMapping
    public ResponseEntity<?> setState(@RequestBody Map<String, Boolean> body) {
        if (body.containsKey("enabled")) {
            enabled.set(body.get("enabled"));
            return ResponseEntity.ok(Map.of("enabled", enabled.get()));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Missing 'enabled' field"));
        }
    }

    // For use by RagService
    public boolean isEnabled() {
        return enabled.get();
    }
} 