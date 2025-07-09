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
        logger.info("RagService initialized with similarity threshold: {}, top-K: {}, skip-query-cleaning: {}, max-context-chars: {}, min-doc-chars: {}, token-limits: {}/{}/{}, and newCachedThreadPool for timeoutExecutor.", 
                   similarityThreshold, topK, skipQueryCleaning, maxContextChars, minDocChars, maxContextTokens, maxResponseTokens, maxTotalTokens);
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
                        return chatClient.prompt()
                            .system("Provide a clear, direct answer to the user's question. Do not include any reasoning, analysis, or thinking process. Be comprehensive and complete in your response.")
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
                String systemPrompt = "You are a helpful AI assistant. Use the provided context and your knowledge to answer the user's question directly and clearly.";
                
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
                        return chatClient.prompt()
                            .system("You are a helpful AI assistant. Use the provided context and your knowledge to answer the user's question directly and clearly.")
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
                    String systemPrompt = "You are a helpful AI assistant. Answer the user's question using ONLY the provided context. If the context doesn't contain enough information, simply state that the information is not available in the provided context.";
                    
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
                            String ragOnlySystemPrompt = "You are a helpful AI assistant. Answer the user's question using ONLY the provided context. If the context doesn't contain enough information, simply state that the information is not available in the provided context.";
                            
                            return chatClient.prompt()
                                .system(ragOnlySystemPrompt)
                                .user(llmSummaryPrompt).call().content();
                        }, this.timeoutExecutor)
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        
                        logger.info("[{}] LLM (RAG Only Non-Streaming) call finished", Instant.now());
                        
                        // Clean the response to remove any reasoning that slipped through
                        String cleanedResponse = extractAnswer(fullResponse);
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
                    String pureLlmSystemPrompt = "You are a helpful AI assistant. Answer the user's question directly and clearly using your knowledge.";
                    
                    return chatClient.prompt()
                        .system(pureLlmSystemPrompt)
                        .user(request.getMessage()).call().content();
                }, this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (statusListener != null) statusListener.onStatus("LLM response received", 90);
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
                    .system("You are a helpful AI assistant. Use the provided context and your knowledge to answer the user's question directly and clearly.")
                    .user(llmPrompt).call().content(), this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (statusListener != null) statusListener.onStatus("LLM response received", 90);
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
                        .system("You are a helpful AI assistant. Answer the user's question using ONLY the provided context. If the context doesn't contain enough information, simply state that the information is not available in the provided context.")
                        .user(llmSummaryPrompt).call().content(), this.timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (statusListener != null) statusListener.onStatus("LLM response received", 90);
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
     * Extract the final answer from LLM response, handling Qwen3 thinking mode
     */
    private String extractAnswer(String llmResponse) {
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return "The provided context does not contain information to answer this question.";
        }
        
        String response = llmResponse.trim();
        
        // Handle Qwen3 thinking mode - extract content after <think>...</think> blocks
        if (response.contains("<think>")) {
            // Find the last </think> tag
            int lastThinkEnd = response.lastIndexOf("</think>");
            if (lastThinkEnd != -1) {
                // Extract everything after the last </think> tag
                String afterThinking = response.substring(lastThinkEnd + "</think>".length()).trim();
                if (!afterThinking.isEmpty()) {
                    logger.info("Extracted answer after Qwen3 thinking block: {}", 
                               afterThinking.substring(0, Math.min(100, afterThinking.length())));
                    return afterThinking;
                }
            }
        }
        
        // If no thinking tags found, return the response as-is
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