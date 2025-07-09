package com.baskettecase.ragui.service;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.concurrent.*;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import com.baskettecase.ragui.controller.QueryExpansionController;

@Service
public class RagService implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    private final ChatClient chatClient;
    private final VectorStoreDocumentRetriever documentRetriever;
    private final ExecutorService timeoutExecutor; // Shared executor for all async tasks
    private static final int TIMEOUT_SECONDS = 180;

    // Make similarity threshold configurable
    @Value("${ragui.vector.similarity-threshold:0.5}")
    private double similarityThreshold;

    @Value("${ragui.vector.top-k:5}")
    private int topK;

    @Value("${ragui.debug.skip-query-cleaning:false}")
    private boolean skipQueryCleaning;

    @Value("${ragui.debug.enable-reasoning:false}")
    private boolean enableReasoning;

    @Value("${ragui.context.max-chars:6000}")
    private int maxContextChars;

    @Value("${ragui.context.min-doc-chars:200}")
    private int minDocChars;

    // Token management configuration
    @Value("${ragui.token.max-total-tokens:8000}")
    private int maxTotalTokens;

    @Value("${ragui.token.max-context-tokens:3000}")
    private int maxContextTokens;

    @Value("${ragui.token.max-response-tokens:2000}")
    private int maxResponseTokens;

    private final QueryExpansionController queryExpansionController;

    public RagService(ChatClient chatClient, VectorStore vectorStore,
                      @Value("${ragui.vector.similarity-threshold:0.5}") double similarityThreshold,
                      @Value("${ragui.vector.top-k:5}") int topK,
                      QueryExpansionController queryExpansionController) {
        this.chatClient = chatClient;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
        this.queryExpansionController = queryExpansionController;
        this.documentRetriever = VectorStoreDocumentRetriever.builder()
            .similarityThreshold(similarityThreshold) // Use configurable threshold
            .topK(topK) // Make top-k configurable too
            .vectorStore(vectorStore)
            .build();
        this.timeoutExecutor = Executors.newCachedThreadPool();
        logger.info("RagService initialized with similarity threshold: {}, top-K: {}, skip-query-cleaning: {}, enable-reasoning: {}, max-context-chars: {}, min-doc-chars: {}, token-limits: {}/{}/{}, and newCachedThreadPool for timeoutExecutor.", 
                   similarityThreshold, topK, skipQueryCleaning, enableReasoning, maxContextChars, minDocChars, maxContextTokens, maxResponseTokens, maxTotalTokens);
    }

    public interface RagStatusListener {
        void onStatus(String statusMessage, int progress);
    }

    public void chatStream(ChatRequest request, RagStatusListener statusListener, Consumer<String> chunkConsumer) {
        String responseMode = determineResponseMode(request);
        logger.info("Processing stream request - Mode: {}, Message: {}", responseMode, request.getMessage());

        try {
            if (statusListener != null) statusListener.onStatus("Received stream request", 10);
            logger.info("[{}] Job stream started for message: {}", Instant.now(), request.getMessage());

            if (request.isUsePureLlm()) {
                if (statusListener != null) statusListener.onStatus("Calling LLM (no RAG) for clean response", 30);
                logger.debug("Using Pure LLM mode for stream: {}", request.getMessage());
                logger.info("[{}] LLM (Pure) call started", Instant.now());
                
                                // Use non-streaming call with direct prompting for quality
                String llmResponse;
                try {
                    llmResponse = CompletableFuture.supplyAsync(() -> {
                                            String systemPrompt = enableReasoning ? 
                        "Provide a clear, direct answer to the user's question. Be comprehensive and complete in your response." :
                        "/no_think Provide a clear, direct answer to the user's question. Do not include any reasoning, analysis, or thinking process. Be comprehensive and complete in your response.";
                        
                        return chatClient.prompt()
                            .system(systemPrompt)
                            .user(request.getMessage())
                            .call()
                            .content();
                    }, this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("[{}] LLM (Pure) call finished", Instant.now());
                } catch (TimeoutException te) {
                    logger.error("LLM (Pure) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("LLM (Pure) call timed out");
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("LLM (Pure) call failed: {}", e.getMessage(), e);
                    throw new RuntimeException("LLM (Pure) call failed: " + e.getMessage());
                }
                
                // Extract clean answer and simulate streaming
                String cleanAnswer = extractAnswer(llmResponse);
                if (statusListener != null) statusListener.onStatus("Streaming clean response", 90);
                simulateStreamingResponse(cleanAnswer, chunkConsumer);
                if (statusListener != null) statusListener.onStatus("LLM stream complete", 100);

            } else             if (request.isIncludeLlmFallback()) { // RAG + LLM Fallback
                if (statusListener != null) statusListener.onStatus("Querying database for relevant context (stream)", 20);
                
                // Apply query expansion if enabled
                String searchQuery = request.getMessage();
                if (queryExpansionController.isEnabled()) {
                    if (statusListener != null) statusListener.onStatus("Expanding query for better retrieval", 15);
                    searchQuery = expandQueryWithLLM(request.getMessage());
                }
                
                logger.debug("Checking for context (threshold {}) for stream message: {}", similarityThreshold, searchQuery);
                Query query = new Query(searchQuery);
                List<Document> docs;
                try {
                    logger.info("[{}] Vector DB (RAG+Fallback Stream) call started", Instant.now());
                    docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), this.timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("[{}] Vector DB (RAG+Fallback Stream) call finished", Instant.now());
                } catch (TimeoutException te) {
                    logger.error("Vector DB (RAG+Fallback Stream) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("Vector DB (RAG+Fallback Stream) call timed out");
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Vector DB (RAG+Fallback Stream) call failed: {}", e.getMessage(), e);
                    throw new RuntimeException("Vector DB (RAG+Fallback Stream) call failed: " + e.getMessage());
                }
                logger.info("Vector DB query (RAG+Fallback Stream) returned {} documents.", docs != null ? docs.size() : 0);
                if (statusListener != null) statusListener.onStatus("Vector DB query complete: " + (docs != null ? docs.size() : 0) + " results", 40);

                String contextText = formatDocumentsToContext(docs);

                // Use token-aware prompt validation
                String systemPrompt = enableReasoning ? 
                    "Answer the question using the provided context and your own knowledge. " +
                    "If the context contains relevant information, use it. " +
                    "If the context doesn't contain the answer, use your general knowledge. " +
                    "Provide a comprehensive answer that combines both sources of information." :
                    "/no_think Answer the question using the provided context and your own knowledge. " +
                    "If the context contains relevant information, use it. " +
                    "If the context doesn't contain the answer, use your general knowledge. " +
                    "Provide a comprehensive answer that combines both sources of information. " +
                    "Do not include any reasoning, analysis, or thinking process.";
                
                String llmPrompt = validateAndAdjustPrompt(contextText, request.getMessage(), systemPrompt);
                
                if (contextText != null && !contextText.isEmpty()) {
                    if (statusListener != null) statusListener.onStatus("Calling LLM with context for clean response", 70);
                } else {
                    if (statusListener != null) statusListener.onStatus("Calling LLM without context for clean response", 70);
                }
                logger.debug("LLM Prompt (RAG+Fallback Mode): User: [{}]", llmPrompt);
                logger.info("[{}] LLM (RAG+Fallback) call started", Instant.now());

                                // Use non-streaming call with direct prompting for quality
                String llmResponse;
                try {
                    llmResponse = CompletableFuture.supplyAsync(() -> {
                        String fallbackSystemPrompt = enableReasoning ? 
                            "Answer the question using the provided context and your own knowledge. " +
                            "If the context contains relevant information, use it. " +
                            "If the context doesn't contain the answer, use your general knowledge. " +
                            "Provide a comprehensive answer that combines both sources of information." :
                            "/no_think Answer the question using the provided context and your own knowledge. " +
                            "If the context contains relevant information, use it. " +
                            "If the context doesn't contain the answer, use your general knowledge. " +
                            "Provide a comprehensive answer that combines both sources of information. " +
                            "Do not include any reasoning, analysis, or thinking process.";
                        
                        return chatClient.prompt()
                            .system(fallbackSystemPrompt)
                            .user(llmPrompt)
                            .call()
                            .content();
                    }, this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("[{}] LLM (RAG+Fallback) call finished", Instant.now());
                } catch (TimeoutException te) {
                    logger.error("LLM (RAG+Fallback) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("LLM (RAG+Fallback) call timed out");
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("LLM (RAG+Fallback) call failed: {}", e.getMessage(), e);
                    throw new RuntimeException("LLM (RAG+Fallback) call failed: " + e.getMessage());
                }
                
                // Extract clean answer and simulate streaming
                String cleanAnswer = extractAnswer(llmResponse);
                if (statusListener != null) statusListener.onStatus("Streaming clean response", 90);
                simulateStreamingResponse(cleanAnswer, chunkConsumer);
                if (statusListener != null) statusListener.onStatus("LLM stream complete", 100);

            } else { // RAG Only (implicitly, as Raw RAG is handled by JobController directly for non-streaming)
                logger.debug("RAG Only mode for stream message: {}", request.getMessage());
                if (statusListener != null) statusListener.onStatus("Sending Prompt to LLM for Pre-Processing (stream)", 15);
                String originalPrompt = request.getMessage();
                String cleanedPrompt = null;
                String lengthConstraint = null;
                try {
                    logger.info("[{}] LLM (Query Cleaning - RAG ONLY STREAM) call starting via CompletableFuture", Instant.now());
                    CleanedQueryResult cleanedResult = CompletableFuture.supplyAsync(() -> cleanQueryWithLlmExtractConstraint(originalPrompt, "RAG ONLY STREAM"), this.timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    cleanedPrompt = cleanedResult.cleanedQuery;
                    lengthConstraint = cleanedResult.lengthConstraint;
                    logger.info("[{}] LLM (Query Cleaning - RAG ONLY STREAM) call finished. Cleaned prompt: '{}'", Instant.now(), cleanedPrompt);
                } catch (TimeoutException te) {
                    logger.error("LLM Pre-Processing (RAG Only Stream) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("LLM Pre-Processing (RAG Only Stream) call timed out");
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("LLM Pre-Processing (RAG Only Stream) call failed: {}", e.getMessage(), e);
                    if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
                    throw new RuntimeException("LLM Pre-Processing (RAG Only Stream) call failed: " + e.getMessage());
                }
                if (statusListener != null) statusListener.onStatus("Pre-Processed Query returned (stream)", 18);
                if (statusListener != null) statusListener.onStatus("Querying vector DB for relevant context (stream)", 20);

                Query query = new Query(cleanedPrompt);
                List<Document> docs;
                try {
                    logger.info("[{}] Vector DB (RAG Only Stream) call started", Instant.now());
                    docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), this.timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("[{}] Vector DB (RAG Only Stream) call finished", Instant.now());
                } catch (TimeoutException te) {
                    logger.error("Vector DB (RAG Only Stream) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("Vector DB (RAG Only Stream) call timed out");
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Vector DB (RAG Only Stream) call failed: {}", e.getMessage(), e);
                    throw new RuntimeException("Vector DB (RAG Only Stream) call failed: " + e.getMessage());
                }
                logger.info("Vector DB query (RAG Only Stream) returned {} documents.", docs != null ? docs.size() : 0);
                if (statusListener != null) statusListener.onStatus("Vector DB query complete: " + (docs != null ? docs.size() : 0) + " results", 40);

                String contextText = formatDocumentsToContext(docs);

                if (contextText != null && !contextText.isEmpty()) {
                    if (statusListener != null) statusListener.onStatus("Calling LLM to analyze context (non-streaming for clean output)", 70);
                    
                    // Use token-aware prompt validation for RAG Only mode
                    String systemPrompt = "Answer the question using ONLY the information provided in the context. " +
                                        "Do not include any reasoning, analysis, or thinking process. " +
                                        "Provide a direct, factual answer based on the context. " +
                                        "If the context does not contain the answer, simply state 'The context does not contain information to answer this question.' " +
                                        "Keep your response concise and to the point.";
                    
                    String basePrompt = validateAndAdjustPrompt(contextText, cleanedPrompt, systemPrompt);
                    
                    String llmSummaryPrompt;
                    if (lengthConstraint != null && !lengthConstraint.isEmpty()) {
                        logger.info("[RAG ONLY STREAM] Appending length constraint to summary prompt: {}", lengthConstraint);
                        llmSummaryPrompt = basePrompt + "\n\nAnswer: " + lengthConstraint;
                    } else {
                        llmSummaryPrompt = basePrompt + "\n\nAnswer:";
                    }
                    
                    logger.info("LLM Prompt (RAG Only Non-Streaming) [first 500 chars]: {}", llmSummaryPrompt.substring(0, Math.min(500, llmSummaryPrompt.length())));
                    logger.info("[{}] LLM (RAG Only Non-Streaming) call started", Instant.now());

                    try {
                        // Use non-streaming to get complete response, then clean it
                        String fullResponse = CompletableFuture.supplyAsync(() -> {
                            String ragOnlySystemPrompt = enableReasoning ? 
                                "Answer the question using ONLY the information provided in the context. " +
                                "If the context does not contain the answer, simply state 'The context does not contain information to answer this question.' " +
                                "Keep your response concise and to the point." :
                                "/no_think Answer the question using ONLY the information provided in the context. " +
                                "Do not include any reasoning, analysis, or thinking process. " +
                                "Provide a direct, factual answer based on the context. " +
                                "If the context does not contain the answer, simply state 'The context does not contain information to answer this question.' " +
                                "Keep your response concise and to the point.";
                            
                            return chatClient.prompt()
                                .system(ragOnlySystemPrompt)
                                .user(llmSummaryPrompt).call().content();
                        }, this.timeoutExecutor)
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        
                        logger.info("[{}] LLM (RAG Only Non-Streaming) call finished", Instant.now());
                        
                        // Clean the response to remove any reasoning that slipped through
                        String cleanedResponse = extractAnswer(fullResponse, true); // true for RAG Only mode
                        logger.info("RAG Only cleaned response: {}", cleanedResponse);
                        
                        // Simulate streaming to maintain consistent UX with other modes
                        simulateStreamingResponse(cleanedResponse, chunkConsumer);
                        if (statusListener != null) statusListener.onStatus("COMPLETED", 100);
                        
                    } catch (TimeoutException te) {
                        logger.error("LLM (RAG Only Non-Streaming) call timed out after {}s", TIMEOUT_SECONDS);
                        chunkConsumer.accept("Request timed out while processing with RAG context.");
                        if (statusListener != null) statusListener.onStatus("LLM timeout error", 100);
                    } catch (Exception e) {
                        logger.error("Error during LLM call (RAG Only Non-Streaming): {}", e.getMessage(), e);
                        chunkConsumer.accept("Error occurred while processing with RAG context.");
                        if (statusListener != null) statusListener.onStatus("LLM error: " + e.getMessage(), 100);
                    }
                } else {
                    logger.info("No context found for RAG Only stream. Completing.");
                    if (statusListener != null) statusListener.onStatus("No context found, stream complete", 90);
                    chunkConsumer.accept("I couldn't find relevant information in the knowledge base to answer your question.");
                    if (statusListener != null) statusListener.onStatus("COMPLETED", 100);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing stream message: " + request.getMessage(), e);
            if (statusListener != null) statusListener.onStatus("Stream processing error: " + e.getMessage(), 100);
            try {
                chunkConsumer.accept("Error during streaming setup: " + e.getMessage());
            } catch (Exception ex) {
                logger.error("Failed to send error chunk to consumer after outer error", ex);
            }
        }
    }

    // Non-streaming chat method for regular requests (not Raw RAG, which is handled in JobController)
    public ChatResponse chat(ChatRequest request, RagStatusListener statusListener) {
        String answer = "An unexpected error occurred.";
        String source = "ERROR";
        String responseMode = determineResponseMode(request);
        logger.info("Processing non-streaming request - Mode: {}, Message: {}", responseMode, request.getMessage());

        try {
            if (statusListener != null) statusListener.onStatus("Received request", 10);

            if (request.isUsePureLlm()) {
                if (statusListener != null) statusListener.onStatus("Calling LLM (no RAG)", 30);
                String llmAnswer = CompletableFuture.supplyAsync(() -> {
                    String pureLlmSystemPrompt = enableReasoning ? 
                        "Provide a clear, direct answer to the user's question. Be comprehensive and complete." :
                        "/no_think Provide a clear, direct answer to the user's question. Do not include any reasoning, analysis, or thinking process. Be comprehensive and complete.";
                    
                    return chatClient.prompt()
                        .system(pureLlmSystemPrompt)
                        .user(request.getMessage()).call().content();
                }, this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (statusListener != null) statusListener.onStatus("LLM response received", 90);
                // Clean reasoning tokens from response - COMMENTED OUT: Using structured prompting now
                // llmAnswer = cleanRawLlmResponse(llmAnswer);
                answer = "LLM Answer:\n" + llmAnswer + "\n\nSource: LLM only";
                source = "LLM";

            } else if (request.isIncludeLlmFallback()) { // RAG + LLM Fallback
                if (statusListener != null) statusListener.onStatus("Querying database for relevant context", 20);
                Query query = new Query(request.getMessage());
                List<Document> docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String contextText = formatDocumentsToContext(docs);
                
                // Use token-aware prompt validation
                String systemPrompt = "Answer the question using the provided context and your own knowledge. " +
                                    "If the context contains relevant information, use it. " +
                                    "If the context doesn't contain the answer, use your general knowledge. " +
                                    "Provide a comprehensive answer that combines both sources of information.";
                String llmPrompt = validateAndAdjustPrompt(contextText, request.getMessage(), systemPrompt);
                String sourceCode = (contextText != null && !contextText.isEmpty()) ? "RAG context + LLM" : "LLM only (no context found)";
                
                if (statusListener != null) statusListener.onStatus("Calling LLM with prompt", 70);
                String llmAnswer = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                    .system("Answer the question using the provided context. Be direct and concise.")
                    .user(llmPrompt).call().content(), this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (statusListener != null) statusListener.onStatus("LLM response received", 90);
                // Clean reasoning tokens from response - COMMENTED OUT: Using structured prompting now
                // llmAnswer = cleanRawLlmResponse(llmAnswer);
                answer = llmAnswer + "\n\nSource: " + sourceCode;
                source = "LLM_FALLBACK";

            } else { // RAG Only
                if (statusListener != null) statusListener.onStatus("Sending Prompt to LLM for Pre-Processing", 15);
                CleanedQueryResult cleanedResult = cleanQueryWithLlmExtractConstraint(request.getMessage(), "RAG ONLY");
                String cleanedPrompt = cleanedResult.cleanedQuery;
                String lengthConstraint = cleanedResult.lengthConstraint;
                
                // Apply query expansion if enabled
                if (queryExpansionController.isEnabled()) {
                    if (statusListener != null) statusListener.onStatus("Expanding query for better retrieval", 18);
                    cleanedPrompt = expandQueryWithLLM(cleanedPrompt);
                }
                
                if (statusListener != null) statusListener.onStatus("Querying vector DB for relevant context", 20);
                Query query = new Query(cleanedPrompt);
                List<Document> docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (statusListener != null) statusListener.onStatus("Vector DB query complete: " + docs.size() + " results", 40);
                String contextText = formatDocumentsToContext(docs);
                if (contextText != null && !contextText.isEmpty()) {
                    if (statusListener != null) statusListener.onStatus("Calling LLM to summarize context", 70);
                    String llmSummaryPromptBase = "Context:\n" + contextText + "\n\nQuestion:\n" + cleanedPrompt + "\n\nAnswer:";
                    String llmSummaryPrompt;
                    if (lengthConstraint != null && !lengthConstraint.isEmpty()) {
                        logger.info("[RAG ONLY] Appending length constraint to summary prompt: {}", lengthConstraint);
                        llmSummaryPrompt = llmSummaryPromptBase + " " + lengthConstraint;
                    } else {
                        llmSummaryPrompt = llmSummaryPromptBase;
                    }
                    String llmSummary = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                        .system("You may think through the question internally, but your response must contain ONLY the final answer based on the provided context. Do not show your reasoning, thinking process, or methodology. Do not include phrases like 'Looking at the context', 'Based on the information', 'Let me think', or any analytical commentary. Provide only the direct, factual answer. If the context doesn't contain the answer, simply state: 'The provided context does not contain information to answer this question.'")
                        .user(llmSummaryPrompt).call().content(), this.timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (statusListener != null) statusListener.onStatus("LLM response received", 90);
                    // Clean reasoning tokens from response - COMMENTED OUT: Using structured prompting now
                    // llmSummary = cleanRawLlmResponse(llmSummary);
                    answer = llmSummary + "\n\nSource: RAG context summarized by LLM";
                    source = "RAG";
                } else {
                    answer = "I couldn't find relevant information in the knowledge base to answer your question.";
                    source = "RAG_NO_CONTEXT";
                }
            }
            if (statusListener != null) statusListener.onStatus("COMPLETED", 100);
        } catch (TimeoutException te) {
            logger.error("Processing non-streaming message timed out: {}", request.getMessage(), te);
            answer = "The request timed out while processing.";
            source = "ERROR_TIMEOUT";
            if (statusListener != null) statusListener.onStatus("Request timed out", 100);
        } catch (Exception e) {
            logger.error("Error processing non-streaming message: {}", request.getMessage(), e);
            answer = "An error occurred: " + e.getMessage();
            source = "ERROR";
            if (statusListener != null) statusListener.onStatus("Processing error: " + e.getMessage(), 100);
        }

        logger.info("Response generated - Source: {}, Mode: {}, Message: {}, Answer [first 200 chars]: {}", 
            source, responseMode, request.getMessage(), answer.substring(0, Math.min(answer.length(), 200)));
        return new ChatResponse.Builder().answer(answer).source(source).build();
    }
    
    /**
     * Processes a raw RAG request, returning a structured response with multiple bubbles.
     * @param request The chat request containing the user's message
     * @return ChatResponse with a list of bubbles for the frontend
     */
    public ChatResponse chatRaw(ChatRequest request) {
        String originalPrompt = request.getMessage();
        logger.info("Processing Raw RAG request - Message: {}", originalPrompt);

        try {
            String searchQuery;
            String lengthConstraint = null;
            
            if (skipQueryCleaning) {
                logger.info("DEBUGGING: Skipping query cleaning, using original prompt directly");
                searchQuery = originalPrompt;
            } else {
                CleanedQueryResult cleanedResult = cleanQueryWithLlmExtractConstraint(originalPrompt, "RAW RAG");
                searchQuery = cleanedResult.cleanedQuery;
                lengthConstraint = cleanedResult.lengthConstraint;
                if (lengthConstraint != null && !lengthConstraint.isEmpty()) {
                    logger.info("[RAW RAG] Length constraint extracted: {}", lengthConstraint);
                }
            }

            // Query expansion for raw RAG
            if (queryExpansionController.isEnabled()) {
                logger.info("[RAW RAG] Expanding query for better retrieval");
                searchQuery = expandQueryWithLLM(searchQuery);
            }
            
            logger.info("VECTOR SEARCH DEBUG - Query to search: '{}'", searchQuery);
            logger.info("VECTOR SEARCH DEBUG - Similarity threshold: {}, Top-K: {}", similarityThreshold, topK);
            
            Query query = new Query(searchQuery);
            List<Document> docs = CompletableFuture.supplyAsync(() -> {
                logger.info("VECTOR SEARCH DEBUG - Starting vector store retrieval...");
                List<Document> results = documentRetriever.retrieve(query);
                logger.info("VECTOR SEARCH DEBUG - Retrieved {} documents", results != null ? results.size() : 0);
                if (results != null && !results.isEmpty()) {
                    for (int i = 0; i < Math.min(results.size(), 3); i++) {
                        Document doc = results.get(i);
                        logger.info("VECTOR SEARCH DEBUG - Doc {}: {} chars, metadata: {}", 
                                   i + 1, 
                                   doc.getFormattedContent().length(), 
                                   doc.getMetadata());
                    }
                }
                return results;
            }, this.timeoutExecutor).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            logger.info("Vector DB query (Raw RAG) returned {} documents.", docs != null ? docs.size() : 0);

            if (docs != null && !docs.isEmpty()) {
                List<String> bubbles = new ArrayList<>();
                bubbles.add("<b>Retrieved Context Documents</b>");

                for (int i = 0; i < docs.size(); i++) {
                    Document doc = docs.get(i);

                    // Bubble for Header
                    StringBuilder headerText = new StringBuilder();
                    headerText.append("<b>Document ").append(i + 1).append(" of ").append(docs.size());
                    if (doc.getMetadata() != null && doc.getMetadata().containsKey("distance")) {
                        Object distObj = doc.getMetadata().get("distance");
                        if (distObj instanceof Number) {
                             headerText.append(String.format(" (distance: %.4f)", ((Number) distObj).floatValue()));
                        } else {
                             headerText.append(" (distance: ").append(distObj.toString()).append(")");
                        }
                    }
                    headerText.append("</b>");
                    bubbles.add(headerText.toString());

                    // Bubble for Content
                    String content = doc.getFormattedContent()
                        .replaceAll("\\s+", " ")
                        .replaceAll("distance:\\s*[0-9.]+", "")
                        .replaceAll("\\s{2,}", " ")
                        .trim();

                    if (!content.isEmpty() && !content.endsWith(".") && !content.endsWith("!") && !content.endsWith("?")) {
                        content = content + ".";
                    }
                    bubbles.add(content);
                }
                String source = String.format("RAG (%d documents retrieved)", docs.size());
                return new ChatResponse.Builder().bubbles(bubbles).source(source).build();
            } else {
                String answer = "I couldn't find any relevant documents in the knowledge base for your question.";
                String source = "RAG (no documents found)";
                return new ChatResponse.Builder().answer(answer).source(source).build();
            }
        } catch (TimeoutException te) {
            String errorMsg = "The request timed out while searching for relevant information.";
            logger.error("Raw RAG processing timed out for message: {}", originalPrompt, te);
            return new ChatResponse.Builder().answer(errorMsg).source("ERROR_TIMEOUT").build();
        } catch (Exception e) {
            String errorMsg = "An error occurred while processing your request: " + e.getMessage();
            logger.error("Error processing Raw RAG request for message: {}", originalPrompt, e);
            return new ChatResponse.Builder().answer(errorMsg).source("ERROR").build();
        }
    }

    /**
     * Estimates token count for a given text (rough approximation: 1 token ≈ 4 characters)
     */
    private int estimateTokens(String text) {
        if (text == null) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }

    /**
     * Formats documents into context with token-aware limits to prevent LLM truncation
     */
    private String formatDocumentsToContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        
        // Use both character and token limits for better control
        final int MAX_CONTEXT_CHARS = maxContextChars;
        final int MIN_DOC_CHARS = minDocChars;
        final int MAX_CONTEXT_TOKENS = maxContextTokens;
        
        StringBuilder context = new StringBuilder();
        int totalChars = 0;
        int totalTokens = 0;
        int docsAdded = 0;
        
        for (Document doc : docs) {
            String content = doc.getFormattedContent();
            int contentLength = content.length();
            int contentTokens = estimateTokens(content);
            
            // Check both character and token limits
            boolean exceedsCharLimit = totalChars + contentLength + 4 > MAX_CONTEXT_CHARS;
            boolean exceedsTokenLimit = totalTokens + contentTokens + 1 > MAX_CONTEXT_TOKENS;
            
            if (exceedsCharLimit || exceedsTokenLimit) {
                // Try to fit a truncated version if we have enough space for meaningful content
                int remainingChars = MAX_CONTEXT_CHARS - totalChars - 4;
                int remainingTokens = MAX_CONTEXT_TOKENS - totalTokens - 1;
                
                if (remainingChars > MIN_DOC_CHARS && remainingTokens > 50) {
                    // Truncate based on the more restrictive limit
                    int maxChars = Math.min(remainingChars, remainingTokens * 4);
                    content = content.substring(0, Math.min(maxChars, contentLength)) + "...";
                    context.append(content).append("\\n\\n");
                    docsAdded++;
                    logger.info("Truncated document {} to fit context limits (chars: {}, tokens: {})", 
                               docsAdded, totalChars + content.length(), totalTokens + estimateTokens(content));
                }
                break; // Stop adding more documents
            }
            
            context.append(content).append("\\n\\n");
            totalChars += contentLength + 4; // +4 for "\\n\\n"
            totalTokens += contentTokens + 1; // +1 for "\\n\\n"
            docsAdded++;
        }
        
        String result = context.toString().trim();
        int finalTokens = estimateTokens(result);
        logger.info("Context formatted: {} characters ({} tokens) from {} documents (max chars: {}, max tokens: {})", 
                   result.length(), finalTokens, docsAdded, MAX_CONTEXT_CHARS, MAX_CONTEXT_TOKENS);
        return result;
    }

    private String determineResponseMode(ChatRequest request) {
        if (request.isRawRag()) return "RAW_RAG"; 
        if (request.isUsePureLlm()) return "PURE_LLM";
        if (request.isIncludeLlmFallback()) return "RAG_WITH_FALLBACK";
        return "RAG_ONLY";
    }
    
    /**
     * Cleans the user query and extracts any length-limiting instruction using the LLM.
     * Returns a CleanedQueryResult containing both the cleaned query and any constraint.
     */
    private CleanedQueryResult cleanQueryWithLlmExtractConstraint(String originalPrompt, String modeTag) {
        String systemPrompt = "Clean and rephrase user queries. Output only the cleaned query."
            + " Fix spelling/grammar and make queries clear."
            + " If there are length constraints (like 'in 20 words'), extract them as: [[LENGTH_CONSTRAINT: ...]]"
            + " Examples:"
            + " Input: 'What is Kubernetes in 20 words?' → Output: What is Kubernetes? [[LENGTH_CONSTRAINT: in 20 words]]"
            + " Input: 'what is platform engineering' → Output: What is platform engineering?";
        
        String cleanedPrompt;
        String constraint = null;
        try {
            logger.info("[{}] [{}] LLM (Query Cleaning) call started", Instant.now(), modeTag);
            String rawResponse = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                .system(systemPrompt)
                .user(originalPrompt)
                .call()
                .content(), this.timeoutExecutor)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            logger.info("RAW LLM RESPONSE (Query Cleaning - {}): {}", modeTag, rawResponse);
            
            // Clean up DeepSeek R1 reasoning tokens and other contamination - COMMENTED OUT: Using structured prompting now
            // cleanedPrompt = cleanRawLlmResponse(rawResponse);
            cleanedPrompt = rawResponse;  // Use raw response directly for now
            logger.info("CLEANED LLM RESPONSE (Query Cleaning - {}): {}", modeTag, cleanedPrompt);
            
            logger.info("[{}] [{}] LLM (Query Cleaning) call finished", Instant.now(), modeTag);
        } catch (TimeoutException te) {
            logger.error("LLM (Query Cleaning) call timed out after {}s [{}]", TIMEOUT_SECONDS, modeTag, te);
            // Fallback: use original prompt if cleaning fails
            logger.warn("Falling back to original prompt due to timeout: {}", originalPrompt);
            return new CleanedQueryResult(originalPrompt, null);
        } catch (Exception e) {
            logger.error("LLM (Query Cleaning) failed [{}] for prompt '{}': {}", modeTag, originalPrompt, e.getMessage(), e);
            // Fallback: use original prompt if cleaning fails
            logger.warn("Falling back to original prompt due to error: {}", originalPrompt);
            return new CleanedQueryResult(originalPrompt, null);
        }
        
        // If cleaned prompt is suspiciously long or contains HTML-like tags, use original
        if (cleanedPrompt.length() > originalPrompt.length() * 3 || cleanedPrompt.contains("<") || cleanedPrompt.contains("think")) {
            logger.warn("LLM response appears contaminated, using original prompt. Contaminated: {}", cleanedPrompt);
            return new CleanedQueryResult(originalPrompt, null);
        }
        
        logger.info("[{}] Original user prompt: {}", modeTag, originalPrompt);
        
        // Extract constraint block if present
        String regex = "\\[\\[LENGTH_CONSTRAINT:(.*?)]]";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(cleanedPrompt);
        if (matcher.find()) {
            constraint = matcher.group(1).trim();
            cleanedPrompt = cleanedPrompt.replace(matcher.group(0), "").trim();
        }
        
        logger.info("[{}] Final cleaned prompt for vector search: '{}'", modeTag, cleanedPrompt);
        if (constraint != null) {
            logger.info("[{}] Length constraint extracted: {}", modeTag, constraint);
        }
        return new CleanedQueryResult(cleanedPrompt, constraint);
    }

    /**
     * Expands a query using the LLM to generate synonyms and related terms.
     * This improves retrieval quality by finding more relevant documents.
     */
    private String expandQueryWithLLM(String originalQuery) {
        try {
            logger.info("[QUERY EXPANSION] Expanding query: '{}'", originalQuery);
            
            String expansionPrompt = String.format(
                "Generate 2-3 synonyms or related terms for the query: '%s'. " +
                "Focus on technical terms, alternative phrasings, and related concepts. " +
                "Return ONLY the expanded query with synonyms separated by spaces. " +
                "Keep it concise and relevant. " +
                "Example: 'spring boot' → 'spring boot springboot java framework'",
                originalQuery
            );
            
            String expandedQuery = CompletableFuture.supplyAsync(() -> {
                return chatClient.prompt()
                    .system("You are a query expansion assistant. Generate relevant synonyms and related terms to improve search results.")
                    .user(expansionPrompt)
                    .call()
                    .content();
            }, this.timeoutExecutor)
            .get(30, TimeUnit.SECONDS); // Shorter timeout for expansion
            
            // Clean the response and combine with original query
            String cleanedExpansion = expandedQuery.trim()
                .replaceAll("\\s+", " ") // Normalize whitespace
                .replaceAll("[^a-zA-Z0-9\\s]", ""); // Remove special characters except spaces
            
            String finalQuery = originalQuery + " " + cleanedExpansion;
            logger.info("[QUERY EXPANSION] Expanded query: '{}' → '{}'", originalQuery, finalQuery);
            
            return finalQuery;
            
        } catch (TimeoutException te) {
            logger.warn("[QUERY EXPANSION] Expansion timed out, using original query: {}", originalQuery);
            return originalQuery;
        } catch (Exception e) {
            logger.warn("[QUERY EXPANSION] Expansion failed, using original query: {} - Error: {}", 
                       originalQuery, e.getMessage());
            return originalQuery;
        }
    }

    /**
     * Validates and adjusts prompt to ensure it stays within token limits
     */
    private String validateAndAdjustPrompt(String contextText, String userQuestion, String systemPrompt) {
        // Estimate tokens for each component
        int contextTokens = estimateTokens(contextText != null ? contextText : "");
        int questionTokens = estimateTokens(userQuestion);
        int systemTokens = estimateTokens(systemPrompt);
        
        // Reserve tokens for response and overhead
        int reservedTokens = maxResponseTokens + 200; // 200 tokens for formatting overhead
        
        // Calculate total estimated tokens
        int totalTokens = contextTokens + questionTokens + systemTokens + reservedTokens;
        
        logger.info("Token estimation - Context: {}, Question: {}, System: {}, Reserved: {}, Total: {} (max: {})", 
                   contextTokens, questionTokens, systemTokens, reservedTokens, totalTokens, maxTotalTokens);
        
        // If we're within limits, return the original prompt
        if (totalTokens <= maxTotalTokens) {
            return contextText != null && !contextText.isEmpty() 
                ? "Context:\n" + contextText + "\n\nUser Question:\n" + userQuestion
                : userQuestion;
        }
        
        // If we're over the limit, we need to reduce context
        int excessTokens = totalTokens - maxTotalTokens;
        logger.warn("Prompt exceeds token limit by {} tokens. Reducing context.", excessTokens);
        
        if (contextText != null && !contextText.isEmpty()) {
            // Calculate how much context we can keep
            int maxContextTokensAllowed = maxContextTokens - excessTokens;
            int maxContextCharsAllowed = maxContextTokensAllowed * 4; // Rough conversion
            
            if (maxContextCharsAllowed > minDocChars) {
                // Truncate context to fit within limits
                String truncatedContext = contextText.length() > maxContextCharsAllowed 
                    ? contextText.substring(0, maxContextCharsAllowed) + "..."
                    : contextText;
                
                logger.info("Truncated context from {} to {} characters to fit token limits", 
                           contextText.length(), truncatedContext.length());
                
                return "Context:\n" + truncatedContext + "\n\nUser Question:\n" + userQuestion;
            } else {
                // Context is too large, use question only
                logger.warn("Context too large for token limits, using question only");
                return userQuestion;
            }
        }
        
        return userQuestion;
    }

    /**
     * Simulate streaming by sending response in chunks to maintain consistent UX
     */
    private void simulateStreamingResponse(String response, Consumer<String> chunkConsumer) {
        if (response == null || response.trim().isEmpty()) {
            return;
        }
        
        // Split response into word-based chunks for natural streaming simulation
        String[] words = response.split("\\s+");
        StringBuilder currentChunk = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            currentChunk.append(words[i]);
            
            // Send chunk every 3-5 words or at sentence boundaries
            if (i == words.length - 1 || // Last word
                currentChunk.length() > 20 || // Reasonable chunk size
                words[i].endsWith(".") || words[i].endsWith("!") || words[i].endsWith("?")) {
                
                String chunkToSend = currentChunk.toString();
                if (i < words.length - 1) {
                    chunkToSend += " "; // Add space except for last chunk
                }
                
                chunkConsumer.accept(chunkToSend);
                
                // Small delay to simulate typing (optional - can be removed if too slow)
                try {
                    Thread.sleep(50); // 50ms delay between chunks
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                
                currentChunk.setLength(0); // Reset chunk
            } else {
                currentChunk.append(" ");
            }
        }
    }


    /**
     * Clean streaming chunks to remove reasoning patterns from LLM
     * COMMENTED OUT: No longer using real streaming, using structured prompting instead
     */
    /*
    private String cleanStreamingChunk(String chunk) {
        if (chunk == null || chunk.trim().isEmpty()) {
            return chunk;
        }
        
        // Skip chunks that contain reasoning patterns - exact patterns from logs
        String lowerChunk = chunk.toLowerCase();
        if (lowerChunk.contains("okay, let's try to figure out") || 
            lowerChunk.contains("first, i need to scan") ||
            lowerChunk.contains("looking through the context") ||
            lowerChunk.contains("looking at the text") ||
            lowerChunk.contains("let me check again") ||
            lowerChunk.contains("i need to recall") ||
            lowerChunk.contains("wait, here's a part") ||
            lowerChunk.contains("wait, there's a section") ||
            lowerChunk.contains("another part mentions") ||
            lowerChunk.contains("another section says") ||
            lowerChunk.contains("hmm, that might be") ||
            lowerChunk.contains("that seems a bit") ||
            lowerChunk.contains("that doesn't make sense") ||
            lowerChunk.contains("maybe the context is") ||
            lowerChunk.contains("alternatively, looking") ||
            lowerChunk.contains("but wait, the user") ||
            lowerChunk.contains("from general knowledge") ||
            lowerChunk.contains("based on the provided") ||
            lowerChunk.contains("according to the context") ||
            lowerChunk.contains("in the context provided") ||
            lowerChunk.contains("context says that") ||
            lowerChunk.contains("the text mentions") ||
            lowerChunk.contains("given the instructions") ||
            lowerChunk.contains("since the context doesn't") ||
            lowerChunk.contains("however, given that") ||
            lowerChunk.contains("therefore, the answer") ||
            lowerChunk.contains("okay,") || 
            lowerChunk.contains("first,") ||
            lowerChunk.contains("looking") ||
            lowerChunk.contains("let me") ||
            lowerChunk.contains("i need") ||
            lowerChunk.contains("wait,") ||
            lowerChunk.contains("hmm,") ||
            lowerChunk.contains("based on") ||
            lowerChunk.contains("according to") ||
            lowerChunk.contains("the context") ||
            lowerChunk.contains("from the") ||
            lowerChunk.contains("in the") ||
            lowerChunk.contains("given that") ||
            lowerChunk.contains("since the") ||
            lowerChunk.contains("however,") ||
            lowerChunk.contains("therefore,") ||
            lowerChunk.contains("alternatively") ||
            lowerChunk.contains("but the") ||
            lowerChunk.contains("so the") ||
            lowerChunk.contains("thus") ||
            lowerChunk.contains("hence") ||
            lowerChunk.contains("consequently")) {
            return ""; // Skip these chunks entirely
        }
        
        return chunk;
    }
    */

    /**
     * Clean raw LLM response by removing reasoning patterns from LLM
     * COMMENTED OUT: No longer needed with structured prompting approach
     */
    /*
    private String cleanRawLlmResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return rawResponse;
        }
        
        String cleaned = rawResponse.trim();
        
        // If response starts with reasoning, try to find the actual answer
        if (cleaned.toLowerCase().startsWith("okay") || 
            cleaned.toLowerCase().startsWith("first") ||
            cleaned.toLowerCase().startsWith("looking") ||
            cleaned.toLowerCase().startsWith("let me") ||
            cleaned.toLowerCase().startsWith("i need") ||
            cleaned.toLowerCase().startsWith("based on") ||
            cleaned.toLowerCase().startsWith("according to")) {
            
            // Split by sentences and find first non-reasoning sentence
            String[] sentences = cleaned.split("\\. ");
            StringBuilder result = new StringBuilder();
            boolean foundAnswer = false;
            
            for (String sentence : sentences) {
                String lowerSentence = sentence.toLowerCase();
                if (!lowerSentence.contains("okay") && 
                    !lowerSentence.contains("first") &&
                    !lowerSentence.contains("looking") &&
                    !lowerSentence.contains("let me") &&
                    !lowerSentence.contains("i need") &&
                    !lowerSentence.contains("wait") &&
                    !lowerSentence.contains("hmm") &&
                    !lowerSentence.contains("based on") &&
                    !lowerSentence.contains("according to") &&
                    !lowerSentence.contains("the context") &&
                    !lowerSentence.contains("from the") &&
                    !lowerSentence.contains("in the") &&
                    !lowerSentence.contains("given that") &&
                    !lowerSentence.contains("since the") &&
                    !lowerSentence.contains("however") &&
                    !lowerSentence.contains("therefore") &&
                    !lowerSentence.contains("alternatively") &&
                    !lowerSentence.contains("but the") &&
                    sentence.trim().length() > 10) {
                    result.append(sentence.trim());
                    if (!sentence.endsWith(".") && !sentence.endsWith("!") && !sentence.endsWith("?")) {
                        result.append(".");
                    }
                    result.append(" ");
                    foundAnswer = true;
                }
            }
            
            if (foundAnswer) {
                cleaned = result.toString().trim();
            } else {
                return "The provided context does not contain information to answer this question.";
            }
        }
        
        // Remove any remaining reasoning patterns
        cleaned = cleaned.replaceAll("(Okay, let's.*?\\.|First, I.*?\\.|Looking.*?\\.|Let me.*?\\.|I need.*?\\.|Wait.*?\\.|Hmm.*?\\.|Based on.*?\\.|According to.*?\\.|The context.*?\\.|From the.*?\\.|In the.*?\\.|Given that.*?\\.|Since the.*?\\.|However.*?\\.|Therefore.*?\\.|Alternatively.*?\\.|But the.*?\\.)", "").trim();
        
        // Remove multiple spaces and clean up
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        
        // If the response is mostly reasoning with little actual content, return a clean error
        if (cleaned.length() < 20 || cleaned.toLowerCase().contains("context does not") || 
            cleaned.toLowerCase().contains("no information") || 
            cleaned.toLowerCase().contains("can't find")) {
            return "The provided context does not contain information to answer this question.";
        }
        
        return cleaned;
    }
    */

    /**
     * Extracts the content from the <answer> tag in the LLM's response.
     * More robust extraction that handles various response formats.
     */
    private String extractAnswer(String llmResponse) {
        return extractAnswer(llmResponse, false);
    }

    /**
     * Extracts the content from the <answer> tag in the LLM's response.
     * More robust extraction that handles various response formats.
     * @param isRagOnly If true, applies stricter filtering for RAG Only mode
     */
    private String extractAnswer(String llmResponse, boolean isRagOnly) {
        // If reasoning is enabled globally, skip filtering
        if (enableReasoning) {
            logger.info("Reasoning enabled globally, returning raw response");
            return llmResponse;
        }
        
        logger.info("Reasoning disabled, applying filtering. Response length: {}", llmResponse.length());
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return "The provided context does not contain information to answer this question.";
        }
        
        String response = llmResponse.trim();
        logger.info("Processing LLM response for answer extraction. Length: {}", response.length());
        
        // First, try to extract from <answer> tags
        java.util.regex.Pattern answerPattern = java.util.regex.Pattern.compile("<answer>(.*?)</answer>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher answerMatcher = answerPattern.matcher(response);
        
        if (answerMatcher.find()) {
            String answer = answerMatcher.group(1).trim();
            logger.info("Successfully extracted answer from <answer> tags: {}", answer.substring(0, Math.min(100, answer.length())));
            return answer;
        }
        
        // If no <answer> tags, try to find text after </thinking> tag
        int thinkingEndIndex = response.lastIndexOf("</thinking>");
        if (thinkingEndIndex != -1) {
            String potentialAnswer = response.substring(thinkingEndIndex + "</thinking>".length()).trim();
            if (!potentialAnswer.isEmpty() && potentialAnswer.length() > 10) {
                logger.info("Found potential answer after </thinking> tag: {}", potentialAnswer.substring(0, Math.min(100, potentialAnswer.length())));
                return potentialAnswer;
            }
        }
        
        // If no structured tags found, check if the response looks like it has thinking content
        if (response.contains("<thinking>") || response.toLowerCase().contains("thinking:")) {
            // Try to extract everything after the thinking section
            String[] parts = response.split("</thinking>");
            if (parts.length > 1) {
                String afterThinking = parts[parts.length - 1].trim();
                if (!afterThinking.isEmpty() && afterThinking.length() > 10) {
                    logger.info("Extracted answer after thinking section: {}", afterThinking.substring(0, Math.min(100, afterThinking.length())));
                    return afterThinking;
                }
            }
        }
        
        // If the response doesn't have structured tags, it might be a direct answer
        // Only do minimal cleaning to avoid truncation
        String cleaned = response;
        
        // Remove obvious thinking prefixes if they exist
        String[] thinkingPrefixes = {
            "thinking:", "let me think", "okay,", "first,", "looking at this", 
            "based on the", "according to the", "from the context"
        };
        
        for (String prefix : thinkingPrefixes) {
            if (cleaned.toLowerCase().startsWith(prefix.toLowerCase())) {
                // Find the first sentence that doesn't start with a thinking prefix
                String[] sentences = cleaned.split("(?<=[.!?])\\s+");
                StringBuilder result = new StringBuilder();
                boolean foundAnswer = false;
                
                for (String sentence : sentences) {
                    String lowerSentence = sentence.toLowerCase().trim();
                    boolean isThinkingSentence = false;
                    
                    for (String thinkingPrefix : thinkingPrefixes) {
                        if (lowerSentence.startsWith(thinkingPrefix.toLowerCase())) {
                            isThinkingSentence = true;
                            break;
                        }
                    }
                    
                    if (!isThinkingSentence && sentence.trim().length() > 10) {
                        result.append(sentence.trim()).append(" ");
                        foundAnswer = true;
                    }
                }
                
                if (foundAnswer) {
                    cleaned = result.toString().trim();
                    logger.info("Cleaned response by removing thinking prefixes: {}", cleaned.substring(0, Math.min(100, cleaned.length())));
                    return cleaned;
                }
            }
        }
        
        // If we get here, the response might be a direct answer without thinking content
        // For RAG Only mode, apply stricter filtering
        if (isRagOnly) {
            logger.info("Applying RAG Only filtering to response");
            // Remove common reasoning patterns that might have slipped through
            String ragOnlyCleaned = response;
            
            // Remove reasoning patterns
            String[] reasoningPatterns = {
                "okay, let's", "first, i'll", "looking at", "let me check", "i need to",
                "based on the", "according to the", "from the context", "the context says",
                "wait,", "hmm,", "however,", "therefore,", "alternatively,", "but wait",
                "that seems", "that might be", "it's possible", "maybe", "perhaps",
                "in the spider-man", "in the context", "the user's question", "the user mentioned",
                "the user is asking", "the context has", "the context contains", "the context includes",
                "the context mentions", "the context states", "the context clearly", "the context also",
                "for example,", "another part", "then there's", "so the answer", "but the question",
                "the direct answer", "the main one", "the answer should be", "the answer is",
                "okay, so", "let me think", "first, i remember", "i should confirm",
                "the text talks about", "there's some confusion", "the context seems",
                "but the user is asking", "even though the context", "the answer is there",
                "i should confirm that", "the main points are", "the context provided",
                "the context is a bit", "the context is not perfectly clear"
            };
            
            for (String pattern : reasoningPatterns) {
                ragOnlyCleaned = ragOnlyCleaned.replaceAll("(?i)" + pattern + ".*?[.!?]\\s*", "");
            }
            
            // Remove sentences that contain reasoning patterns
            String[] sentences = ragOnlyCleaned.split("(?<=[.!?])\\s+");
            StringBuilder cleanedSentences = new StringBuilder();
            
            for (String sentence : sentences) {
                String lowerSentence = sentence.toLowerCase().trim();
                boolean shouldRemove = false;
                
                // Check if sentence contains reasoning patterns
                for (String pattern : reasoningPatterns) {
                    if (lowerSentence.contains(pattern.toLowerCase())) {
                        shouldRemove = true;
                        break;
                    }
                }
                
                // Also check for common reasoning indicators
                if (lowerSentence.contains("the user") || 
                    lowerSentence.contains("the context") ||
                    lowerSentence.contains("for example") ||
                    lowerSentence.contains("another part") ||
                    lowerSentence.contains("then there's") ||
                    lowerSentence.contains("so the answer") ||
                    lowerSentence.contains("but the question") ||
                    lowerSentence.contains("the direct answer") ||
                    lowerSentence.contains("the main one") ||
                    lowerSentence.contains("the answer should be") ||
                    lowerSentence.contains("the answer is") ||
                    lowerSentence.contains("okay, so") ||
                    lowerSentence.contains("let me think") ||
                    lowerSentence.contains("first, i remember") ||
                    lowerSentence.contains("i should confirm") ||
                    lowerSentence.contains("the text talks about") ||
                    lowerSentence.contains("there's some confusion") ||
                    lowerSentence.contains("the context seems") ||
                    lowerSentence.contains("but the user is asking") ||
                    lowerSentence.contains("even though the context") ||
                    lowerSentence.contains("the answer is there") ||
                    lowerSentence.contains("i should confirm that")) {
                    shouldRemove = true;
                }
                
                if (!shouldRemove && sentence.trim().length() > 5) {
                    cleanedSentences.append(sentence.trim()).append(" ");
                }
            }
            
            ragOnlyCleaned = cleanedSentences.toString().trim();
            
            // Remove multiple spaces and clean up
            ragOnlyCleaned = ragOnlyCleaned.replaceAll("\\s{2,}", " ").trim();
            
            logger.info("RAG Only filtering complete. Original sentences: {}, Cleaned sentences: {}", 
                       sentences.length, cleanedSentences.toString().split("(?<=[.!?])\\s+").length);
            
            // If the cleaned response is significantly shorter, use it
            if (ragOnlyCleaned.length() > 20 && ragOnlyCleaned.length() < response.length() * 0.8) {
                logger.info("Applied RAG Only filtering. Original: {} chars, Cleaned: {} chars", 
                           response.length(), ragOnlyCleaned.length());
                return ragOnlyCleaned;
            }
        }
        
        // Return it as-is to avoid truncation
        logger.info("Using raw response as answer (no structured tags or thinking content found): {}", 
                   response.substring(0, Math.min(100, response.length())));
        return response;
    }

    /**
     * Container for cleaned query and optional constraint.
     */
    private static class CleanedQueryResult {
        public final String cleanedQuery;
        public final String lengthConstraint;
        public CleanedQueryResult(String cleanedQuery, String lengthConstraint) {
            this.cleanedQuery = cleanedQuery;
            this.lengthConstraint = lengthConstraint;
        }
    }


    @Override
    public void destroy() throws Exception {
        if (this.timeoutExecutor != null && !this.timeoutExecutor.isShutdown()) {
            logger.info("Shutting down timeoutExecutor in RagService as part of DisposableBean.destroy()");
            this.timeoutExecutor.shutdown();
            try {
                if (!this.timeoutExecutor.awaitTermination(10, TimeUnit.SECONDS)) { 
                    logger.warn("timeoutExecutor did not terminate in 10 seconds, forcing shutdown.");
                    this.timeoutExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                logger.warn("Interrupted while waiting for timeoutExecutor to terminate, forcing shutdown.");
                this.timeoutExecutor.shutdownNow();
                Thread.currentThread().interrupt(); 
            }
            logger.info("timeoutExecutor shutdown process completed.");
        }
    }
}