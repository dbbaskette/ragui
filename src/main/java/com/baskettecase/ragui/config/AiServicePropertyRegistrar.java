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
    private CloudFoundryAiConfig.AiServiceProperties embedServiceProperties;

    @Autowired
    private ConfigurableEnvironment environment;

    @PostConstruct
    public void registerAiProperties() {
        Map<String, Object> map = new HashMap<>();
        // Chat model properties
        map.put("spring.ai.openai.base-url", aiServiceProperties.getApiBase());
        map.put("spring.ai.openai.api-key", aiServiceProperties.getApiKey());
        map.put("spring.ai.openai.chat.model", aiServiceProperties.getModelName());
        // Embedding model properties
        map.put("spring.ai.openai.embedding.base-url", embedServiceProperties.getApiBase());
        map.put("spring.ai.openai.embedding.api-key", embedServiceProperties.getApiKey());
        map.put("spring.ai.openai.embedding.model", embedServiceProperties.getModelName());
        environment.getPropertySources().addFirst(new MapPropertySource("aiServiceProperties", map));
    }
}
