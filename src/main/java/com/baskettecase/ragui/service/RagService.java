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

    public RagService(ChatClient chatClient, VectorStore vectorStore,
                      @Value("${ragui.vector.similarity-threshold:0.5}") double similarityThreshold,
                      @Value("${ragui.vector.top-k:5}") int topK) {
        this.chatClient = chatClient;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
        this.documentRetriever = VectorStoreDocumentRetriever.builder()
            .similarityThreshold(similarityThreshold) // Use configurable threshold
            .topK(topK) // Make top-k configurable too
            .vectorStore(vectorStore)
            .build();
        this.timeoutExecutor = Executors.newCachedThreadPool();
        logger.info("RagService initialized with similarity threshold: {}, top-K: {}, skip-query-cleaning: {}, and newCachedThreadPool for timeoutExecutor.", 
                   similarityThreshold, topK, skipQueryCleaning);
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
                
                // Use non-streaming call with structured prompting for quality
                String llmResponse;
                try {
                    llmResponse = CompletableFuture.supplyAsync(() -> {
                        return chatClient.prompt()
                            .system("Step 1: Think about your response in a <thinking> block. " +
                                   "Step 2: Provide your final answer in an <answer> block. " +
                                   "Always include both blocks in your response.")
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

            } else if (request.isIncludeLlmFallback()) { // RAG + LLM Fallback
                if (statusListener != null) statusListener.onStatus("Querying database for relevant context (stream)", 20);
                logger.debug("Checking for context (threshold {}) for stream message: {}", similarityThreshold, request.getMessage());
                Query query = new Query(request.getMessage());
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

                String llmPrompt;
                if (contextText != null && !contextText.isEmpty()) {
                    llmPrompt = "Context:\n" + contextText + "\n\nUser Question:\n" + request.getMessage();
                    if (statusListener != null) statusListener.onStatus("Calling LLM with context for clean response", 70);
                } else {
                    llmPrompt = request.getMessage();
                    if (statusListener != null) statusListener.onStatus("Calling LLM without context for clean response", 70);
                }
                logger.debug("LLM Prompt (RAG+Fallback Mode): User: [{}]", llmPrompt);
                logger.info("[{}] LLM (RAG+Fallback) call started", Instant.now());

                // Use non-streaming call with structured prompting for quality
                String llmResponse;
                try {
                    llmResponse = CompletableFuture.supplyAsync(() -> {
                        return chatClient.prompt()
                            .system("Step 1: Think about your response in a <thinking> block. " +
                                   "Step 2: Provide your final answer in an <answer> block. " +
                                   "Always include both blocks in your response.")
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
                    String llmSummaryPrompt;
                    {
                        String llmSummaryPromptBase = "Context:\n" + contextText + "\n\nQuestion:\n" + cleanedPrompt + "\n\nAnswer:";
                        if (lengthConstraint != null && !lengthConstraint.isEmpty()) {
                            logger.info("[RAG ONLY STREAM] Appending length constraint to summary prompt: {}", lengthConstraint);
                            llmSummaryPrompt = llmSummaryPromptBase + " " + lengthConstraint;
                        } else {
                            llmSummaryPrompt = llmSummaryPromptBase;
                        }
                    }
                    logger.info("LLM Prompt (RAG Only Non-Streaming) [first 500 chars]: {}", llmSummaryPrompt.substring(0, Math.min(500, llmSummaryPrompt.length())));
                    logger.info("[{}] LLM (RAG Only Non-Streaming) call started", Instant.now());

                    try {
                        // Use non-streaming to get complete response, then clean it
                        String fullResponse = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                            .system("You are a helpful assistant. Your task is to follow a strict two-step process. " +
                                    "Step 1: First, think through the user's question step-by-step in a <thinking> block. Analyze the provided context thoroughly. " +
                                    "Step 2: After your thinking, you MUST provide a concise, final answer based on your thinking and the context within an <answer> block. " +
                                    "The user will ONLY see what is inside the <answer> block. Do NOT write anything outside the <thinking> and <answer> blocks. " +
                                    "If the context does not contain the answer, state that clearly inside the <answer> block.")
                            .user(llmSummaryPrompt).call().content(), this.timeoutExecutor)
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
                String llmAnswer = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                    .system("Answer directly and concisely. Do not explain your reasoning or thought process.")
                    .user(request.getMessage()).call().content(), this.timeoutExecutor)
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
                String llmPrompt = (contextText != null && !contextText.isEmpty()) 
                    ? "Context:\n" + contextText + "\n\nUser Question:\n" + request.getMessage()
                    : request.getMessage();
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

    private String formatDocumentsToContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        return docs.stream()
            .map(Document::getFormattedContent)
            .collect(Collectors.joining("\\n\\n"));
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
     */
    private String extractAnswer(String llmResponse) {
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return "The provided context does not contain information to answer this question.";
        }
        
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<answer>(.*?)</answer>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(llmResponse);
        
        if (matcher.find()) {
            String answer = matcher.group(1).trim();
            logger.info("Extracted answer: {}", answer);
            return answer;
        }
        
        logger.warn("Could not find <answer> tag in LLM response. Raw response: {}", llmResponse);
        
        // Fallback: If no <answer> tag, try to find text after </thinking>
        int thinkingEndIndex = llmResponse.lastIndexOf("</thinking>");
        if (thinkingEndIndex != -1) {
            String potentialAnswer = llmResponse.substring(thinkingEndIndex + "</thinking>".length()).trim();
            if (!potentialAnswer.isEmpty()) {
                logger.warn("Found potential answer after </thinking> tag: {}", potentialAnswer);
                return potentialAnswer;
            }
        }
        
        // Final fallback
        return "Could not extract a final answer from the model's response. Please try rephrasing your question.";
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