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
        // Print the full VCAP_SERVICES at startup for debugging
        String vcapServices = System.getenv("VCAP_SERVICES");
        logger.debug("[CFENV] VCAP_SERVICES at startup: {}", vcapServices);

        CfEnv cfEnv = new CfEnv();
        var chatModel = cfEnv.findServiceByName("chat-model");
        if (chatModel == null) {
            // Log all available service names and labels for troubleshooting
            StringBuilder availableNames = new StringBuilder();
            StringBuilder availableLabels = new StringBuilder();
            cfEnv.findAllServices().forEach(svc -> {
                availableNames.append(svc.getName()).append(", ");
                availableLabels.append(svc.getLabel()).append(", ");
            });
            logger.error("[CFENV] No service with label or name 'chat-model' found. Available service names: [{}], labels: [{}]", availableNames, availableLabels);
            throw new IllegalStateException("No service with label or name 'chat-model' found in VCAP_SERVICES. Names: [" + availableNames + "] Labels: [" + availableLabels + "]");
        }
        String apiBase = chatModel.getCredentials().getString("api_base");
        String apiKey = chatModel.getCredentials().getString("api_key");
        String modelName = chatModel.getCredentials().getString("model_name");

        logger.info("[CFENV] Extracted chat-model.api_base: {}", apiBase);
        logger.info("[CFENV] Extracted chat-model.api_key: {}", apiKey != null ? "***REDACTED***" : "null");
        logger.info("[CFENV] Extracted chat-model.model_name: {}", modelName);

        return new AiServiceProperties(apiBase, apiKey, modelName);
    }

    @Bean
    public AiServiceProperties embedServiceProperties() {
        CfEnv cfEnv = new CfEnv();
        var embedModel = cfEnv.findServiceByName("embed-model");
        if (embedModel == null) {
            // Log all available service names and labels for troubleshooting
            StringBuilder availableNames = new StringBuilder();
            StringBuilder availableLabels = new StringBuilder();
            cfEnv.findAllServices().forEach(svc -> {
                availableNames.append(svc.getName()).append(", ");
                availableLabels.append(svc.getLabel()).append(", ");
            });
            logger.error("[CFENV] No service with label or name 'embed-model' found. Available service names: [{}], labels: [{}]", availableNames, availableLabels);
            throw new IllegalStateException("No service with label or name 'embed-model' found in VCAP_SERVICES. Names: [" + availableNames + "] Labels: [" + availableLabels + "]");
        }
        String apiBase = embedModel.getCredentials().getString("api_base");
        String apiKey = embedModel.getCredentials().getString("api_key");
        String modelName = embedModel.getCredentials().getString("model_name");

        logger.info("[CFENV] Extracted embed-model.api_base: {}", apiBase);
        logger.info("[CFENV] Extracted embed-model.api_key: {}", apiKey != null ? "***REDACTED***" : "null");
        logger.info("[CFENV] Extracted embed-model.model_name: {}", modelName);

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
