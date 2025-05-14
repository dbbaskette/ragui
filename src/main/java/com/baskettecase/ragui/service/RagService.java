package com.baskettecase.ragui.service;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;

@Service
public class RagService {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    public ChatResponse chat(ChatRequest request) {
        String answer = chatClient.prompt()
            .advisors(new QuestionAnswerAdvisor(vectorStore))
            .user(request.getMessage())
            .call()
            .content();

        // Manual fallback: if RAG returns a fallback/empty answer, use LLM directly
        if (answer == null || answer.trim().isEmpty() ||
            answer.toLowerCase().contains("i don't know") ||
            answer.toLowerCase().contains("no relevant information") ||
            answer.toLowerCase().contains("apologize")) {
            answer = chatClient.prompt()
                .user(request.getMessage())
                .call()
                .content();
        }
        return new ChatResponse(answer);
    }

  

}


