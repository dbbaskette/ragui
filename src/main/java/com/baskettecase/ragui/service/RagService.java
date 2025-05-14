package com.baskettecase.ragui.service;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * RagService provides a chat interface that uses Retrieval-Augmented Generation (RAG) with fallback to LLM.
 * <p>
 * This service first attempts to answer user queries using context retrieved from a vector store via the
 * QuestionAnswerAdvisor. If no relevant context is found or the advisor returns a fallback response, the service
 * automatically falls back to the base language model (LLM) to generate an answer using its own knowledge.
 */
@Service
public class RagService {
    /**
     * The ChatClient used to interact with the LLM and RAG advisor.
     */
    private final ChatClient chatClient;
    /**
     * The VectorStore used for semantic retrieval of context documents.
     */
    private final VectorStore vectorStore;

    /**
     * Constructs a RagService with the given ChatClient and VectorStore.
     *
     * @param chatClient   the chat client for LLM and advisor interactions
     * @param vectorStore  the vector store for context retrieval
     */
    public RagService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    /**
     * Handles a chat request with RAG and LLM fallback.
     * <p>
     * Attempts to answer using the QuestionAnswerAdvisor (RAG). If the advisor returns an empty or fallback response,
     * the method falls back to the base LLM for a general answer.
     *
     * @param request the chat request containing the user's message
     * @return a ChatResponse with the answer from RAG or LLM
     */
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


