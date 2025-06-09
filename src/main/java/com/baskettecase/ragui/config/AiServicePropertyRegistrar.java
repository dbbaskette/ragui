package com.baskettecase.ragui.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class AiServicePropertyRegistrar {
    @Bean
    public static PropertySource<?> aiServicePropertySource(
            CloudFoundryAiConfig.AiServiceProperties aiServiceProperties,
            CloudFoundryAiConfig.AiServiceProperties embedServiceProperties) {
        Map<String, Object> map = new HashMap<>();
        // Chat model properties
        map.put("spring.ai.openai.base-url", aiServiceProperties.getApiBase());
        map.put("spring.ai.openai.api-key", aiServiceProperties.getApiKey());
        map.put("spring.ai.openai.chat.model", aiServiceProperties.getModelName());
        // Embedding model properties
        map.put("spring.ai.openai.embedding.base-url", embedServiceProperties.getApiBase());
        map.put("spring.ai.openai.embedding.api-key", embedServiceProperties.getApiKey());
        map.put("spring.ai.openai.embedding.model", embedServiceProperties.getModelName());
        return new MapPropertySource("aiServiceProperties", map);
    }
}

