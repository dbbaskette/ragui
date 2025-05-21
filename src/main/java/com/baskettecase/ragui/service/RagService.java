package com.baskettecase.ragui.service;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

/**
 * RagService provides a chat interface that uses Retrieval-Augmented Generation (RAG) with fallback to LLM.
 * <p>
 * This service first attempts to answer user queries using context retrieved from a vector store via the
 * QuestionAnswerAdvisor. If no relevant context is found or the advisor returns a fallback response, the service
 * automatically falls back to the base language model (LLM) to generate an answer using its own knowledge.
 */
@Service
/**
 * RagService provides a chat interface that uses Retrieval-Augmented Generation (RAG) with optional LLM fallback.
 *
 * The service first attempts to answer user queries using context retrieved from a vector store (RAG).
 * If no relevant context is found or the advisor returns a fallback response (e.g., "I don't know"),
 * and the includeLlmFallback flag is enabled, the service automatically falls back to the base language model (LLM)
 * to generate an answer using its own knowledge.
 */
public class RagService {
    /**
     * The ChatClient used to interact with the LLM and RAG advisor.
     */
    private final ChatClient chatClient;
    /**
     * The VectorStore used for semantic retrieval of context documents.
     */
    private final VectorStore vectorStore;
    private final Advisor retrievalAugmentationAdvisor;

    /**
     * Constructs a RagService with the given ChatClient and VectorStore.
     * Initializes a custom RetrievalAugmentationAdvisor with a similarity threshold.
     *
     * @param chatClient   the chat client for LLM and advisor interactions
     * @param vectorStore  the vector store for context retrieval
     */
    public RagService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
            .documentRetriever(VectorStoreDocumentRetriever.builder()
                .similarityThreshold(0.50)
                .vectorStore(vectorStore)
                .build())
            .build();
    }


    /**
     * Handles a chat request with RAG and optional LLM fallback.
     * <p>
     * If includeLlmFallback is true and the RAG answer is a fallback/empty, the method will
     * call the LLM directly and return that answer instead.
     *
     * @param request the chat request containing the user's message and fallback option
     * @return a ChatResponse with the answer and its source ("RAG", "LLM", or "ERROR")
     */
    /**
     * Handles a chat request using RAG and, optionally, LLM fallback.
     *
     * If includeLlmFallback is true and the RAG answer is a fallback or empty, the method will
     * call the LLM directly and return that answer instead. The response source will be set to
     * "RAG" (vector context used), "LLM" (LLM fallback used), or "ERROR" (exception occurred).
     *
     * @param request the chat request containing the user's message and fallback option
     * @return a ChatResponse with the answer and its source
     */
    public ChatResponse chat(ChatRequest request) {
        String answer;
        String source = "RAG";
        try {
            // If pure LLM mode is enabled, bypass RAG entirely
            if (request.isUsePureLlm()) {
                answer = chatClient.prompt()
                    .user(request.getMessage())
                    .call()
                    .content();
                source = "LLM";
                System.out.println("[RAGService] Pure LLM response for question: '" + request.getMessage() + "' => " + answer);
            } else {
                // Use RAG (vector store context)
                answer = chatClient.prompt()
                    .advisors(retrievalAugmentationAdvisor)
                    .user(request.getMessage())
                    .call()
                    .content();
                System.out.println("[RAGService] RAG response for question: '" + request.getMessage() + "' => " + answer);

                // If LLM fallback is enabled and the answer is a fallback/empty, query the LLM directly
                if (request.isIncludeLlmFallback() &&
                    (answer == null || answer.trim().isEmpty() ||
                     answer.toLowerCase().contains("i don't know") ||
                     answer.toLowerCase().contains("no relevant information") ||
                     answer.toLowerCase().contains("apologize"))) {
                    String ragAnswer = answer;
                    answer = chatClient.prompt()
                        .user(request.getMessage())
                        .call()
                        .content();
                    source = "LLM";
                    System.out.println("[RAGService] LLM fallback response for question: '" + request.getMessage() + "' (RAG was: '" + ragAnswer + "') => " + answer);
                }
            }
        } catch (Exception e) {
            answer = "An error occurred while processing your request.";
            source = "ERROR";
            System.err.println("ERROR in RagService.chat: " + e.getMessage());
            e.printStackTrace();
            return new ChatResponse(answer, source);
        }
        return new ChatResponse(answer, source);
    }

    
  

}
