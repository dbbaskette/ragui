package com.baskettecase.ragui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
// Removed explicit OpenAI imports as we'll rely on auto-configuration

/**
 * AiConfig configures beans for Spring AI integration, primarily the ChatClient.
 * ChatModel is expected to be auto-configured by Spring AI based on properties
 * or VCAP services if running in Cloud Foundry.
 */
@Configuration
public class AiConfig {

    // These @Value annotations are primarily for local development clarity
    // or if you need to directly access these properties for other reasons.
    // Spring AI auto-configuration will use similar properties from application.properties
    // or environment variables (e.g., spring.ai.openai.api-key, spring.ai.openai.chat.options.model).
    // When on Cloud Foundry, VCAP services should provide these, and Spring Boot will map them.

    @Value("${spring.ai.openai.api-key:YOUR_LOCAL_API_KEY_IF_NEEDED}")
    private String apiKey; // Used by auto-configuration if no VCAP service provides it

    @Value("${spring.ai.openai.chat.options.model:gpt-3.5-turbo}")
    private String modelName; // Used by auto-configuration

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        // Spring AI auto-configures the ChatModel bean (e.g., OpenAiChatModel)
        // based on classpath dependencies and properties (application.properties or VCAP).
        return ChatClient.builder(chatModel).build();
    }
}
