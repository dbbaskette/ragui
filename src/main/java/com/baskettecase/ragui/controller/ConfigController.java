package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.config.CloudConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final CloudConfig cloudConfig;

    public ConfigController(CloudConfig cloudConfig) {
        this.cloudConfig = cloudConfig;
    }

    @GetMapping("/properties")
    public Map<String, Object> getConfigProperties() {
        return cloudConfig.getImportantProperties();
    }
}
