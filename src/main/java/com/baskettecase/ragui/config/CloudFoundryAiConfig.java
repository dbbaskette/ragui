package com.baskettecase.ragui.config;

import io.pivotal.cfenv.core.CfEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudFoundryAiConfig {
    private static final Logger logger = LoggerFactory.getLogger(CloudFoundryAiConfig.class);

    @Bean
    public AiServiceProperties aiServiceProperties() {
        CfEnv cfEnv = new CfEnv();
        var chatModel = cfEnv.findServiceByLabel("chat-model");
        if (chatModel == null) {
            throw new IllegalStateException("No service with label 'chat-model' found in VCAP_SERVICES");
        }
        String apiBase = chatModel.getCredentials().getString("api_base");
        String apiKey = chatModel.getCredentials().getString("api_key");
        String modelName = chatModel.getCredentials().getString("model_name");

        logger.info("[CFENV] Extracted chat-model.api_base: {}", apiBase);
        logger.info("[CFENV] Extracted chat-model.api_key: {}", apiKey != null ? "***REDACTED***" : "null");
        logger.info("[CFENV] Extracted chat-model.model_name: {}", modelName);

        return new AiServiceProperties(apiBase, apiKey, modelName);
    }

    public static class AiServiceProperties {
        private final String apiBase;
        private final String apiKey;
        private final String modelName;

        public AiServiceProperties(String apiBase, String apiKey, String modelName) {
            this.apiBase = apiBase;
            this.apiKey = apiKey;
            this.modelName = modelName;
        }

        public String getApiBase() { return apiBase; }
        public String getApiKey() { return apiKey; }
        public String getModelName() { return modelName; }
    }
}
