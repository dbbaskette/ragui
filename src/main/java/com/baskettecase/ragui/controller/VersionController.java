package com.baskettecase.ragui.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class VersionController {
    @GetMapping("/version")
    public Map<String, String> getVersion() {
        Map<String, String> version = new HashMap<>();
        version.put("version", "0.0.60"); // Update if you want to fetch dynamically
        return version;
    }
}
