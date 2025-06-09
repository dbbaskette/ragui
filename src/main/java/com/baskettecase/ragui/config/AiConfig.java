package com.baskettecase.ragui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import com.baskettecase.ragui.config.CloudFoundryAiConfig.AiServiceProperties;

/**
 * AiConfig configures beans for Spring AI integration, including the ChatClient and related models.
 */
@Configuration
public class AiConfig {

    @Autowired
    private AiServiceProperties aiServiceProperties;

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
