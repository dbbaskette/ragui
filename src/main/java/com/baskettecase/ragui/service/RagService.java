package com.baskettecase.ragui.service;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    
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
        String responseMode = determineResponseMode(request);
        
        // Log the incoming request and selected response mode
        logger.info("Processing request - Mode: {}, Message: {}", responseMode, request.getMessage());
        
        try {
            // If pure LLM mode is enabled, bypass RAG entirely
            if (request.isUsePureLlm()) {
                logger.debug("Using Pure LLM mode for message: {}", request.getMessage());
                answer = chatClient.prompt()
                    .user(request.getMessage())
                    .call()
                    .content();
                source = "LLM";
                logger.debug("Pure LLM response for message '{}': {}", request.getMessage(), answer);
            } else {
                // Use RAG (vector store context)
                logger.debug("Using RAG mode for message: {}", request.getMessage());
                answer = chatClient.prompt()
                    .advisors(retrievalAugmentationAdvisor)
                    .user(request.getMessage())
                    .call()
                    .content();
                logger.debug("RAG response for message '{}': {}", request.getMessage(), answer);

                // If LLM fallback is enabled and the answer is a fallback/empty, query the LLM directly
                if (request.isIncludeLlmFallback() && isFallbackResponse(answer)) {
                    logger.debug("Triggering LLM fallback for message: {}", request.getMessage());
                    String ragAnswer = answer;
                    answer = chatClient.prompt()
                        .user(request.getMessage())
                        .call()
                        .content();
                    source = "LLM";
                    logger.debug("LLM fallback response for message '{}' (RAG was: '{}'): {}", 
                        request.getMessage(), ragAnswer, answer);
                }
            }
            
            // Log the final response and source
            logger.info("Response generated - Source: {}, Mode: {}, Message: {}, Answer: {}", 
                source, responseMode, request.getMessage(), answer);
                
        } catch (Exception e) {
            answer = "An error occurred while processing your request.";
            source = "ERROR";
            logger.error("Error processing message: " + request.getMessage(), e);
            return new ChatResponse(answer, source);
        }
        return new ChatResponse(answer, source);
    }
    
    /**
     * Determines the response mode based on the request parameters.
     */
    private String determineResponseMode(ChatRequest request) {
        if (request.isUsePureLlm()) {
            return "PURE_LLM";
        } else if (request.isIncludeLlmFallback()) {
            return "RAG_WITH_FALLBACK";
        } else {
            return "RAG_ONLY";
        }
    }
    
    /**
     * Checks if the response is a fallback/empty response.
     */
    private boolean isFallbackResponse(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return true;
        }
        String lowerAnswer = answer.toLowerCase();
        return lowerAnswer.contains("i don't know") ||
               lowerAnswer.contains("no relevant information") ||
               lowerAnswer.contains("apologize") ||
               lowerAnswer.contains("i don't have enough information");
    }
}
