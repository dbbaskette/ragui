package com.baskettecase.ragui.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class AiServicePropertyRegistrar {
    @Autowired
    private CloudFoundryAiConfig.AiServiceProperties aiServiceProperties;

    @Autowired
    private ConfigurableEnvironment environment;

    @PostConstruct
    public void registerAiProperties() {
        Map<String, Object> map = new HashMap<>();
        map.put("spring.ai.openai.base-url", aiServiceProperties.getApiBase());
        map.put("spring.ai.openai.api-key", aiServiceProperties.getApiKey());
        map.put("spring.ai.openai.chat.model", aiServiceProperties.getModelName());
        environment.getPropertySources().addFirst(new MapPropertySource("aiServiceProperties", map));
    }
}
