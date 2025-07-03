package com.baskettecase.ragui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;
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
        
        // If this is an OpenAI model, configure it to disable thinking mode
        if (chatModel instanceof OpenAiChatModel openAiChatModel) {
            // Create default options optimized for thinking + complete answers
            OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .maxTokens(4096) // Increase max tokens significantly to accommodate thinking + answer
                .temperature(0.3) // Lower temperature for more focused responses
                .topP(0.9) // Focus probability mass
                .build();
            
            return ChatClient.builder(chatModel)
                .defaultOptions(defaultOptions)
                .build();
        }
        
        return ChatClient.builder(chatModel).build();
    }
}
