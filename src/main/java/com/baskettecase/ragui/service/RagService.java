package com.baskettecase.ragui.service;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Service;

@Service
public class RagService {
    private final ChatClient chatClient;
    private final RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;

    public RagService(ChatClient chatClient, RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        this.chatClient = chatClient;
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
    }

    public ChatResponse chat(ChatRequest request) {
        String answer = chatClient.prompt()
            .advisors(retrievalAugmentationAdvisor)
            .user(request.getMessage())
            .call()
            .content();
        return new ChatResponse(answer);
    }
}
