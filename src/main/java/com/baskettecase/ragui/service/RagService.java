package com.baskettecase.ragui.service;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.rag.Query;
import java.util.List;
import java.util.concurrent.*;
import java.time.Instant;
import java.util.function.Consumer;

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

    public void chatStream(ChatRequest request, RagStatusListener statusListener, Consumer<String> chunkConsumer) {
        String responseMode = determineResponseMode(request);
        logger.info("Processing stream request - Mode: {}, Message: {}", responseMode, request.getMessage());
        ExecutorService timeoutExecutor = Executors.newSingleThreadExecutor();

        try {
            if (statusListener != null) statusListener.onStatus("Received stream request", 10);
            logger.info("[{}] Job stream started for message: {}", Instant.now(), request.getMessage());

            if (request.isUsePureLlm()) {
                if (statusListener != null) statusListener.onStatus("Calling LLM (no RAG) for streaming", 30);
                logger.debug("Using Pure LLM mode for stream: {}", request.getMessage());
                if (springEnv != null) {
                    String baseUrl = springEnv.getProperty("spring.ai.openai.base-url");
                    logger.debug("[DEBUG] spring.ai.openai.base-url before LLM stream call: {}", baseUrl);
                }
                logger.info("[{}] LLM (Pure Stream) call started", Instant.now());
                chatClient.prompt()
                    .user(request.getMessage())
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        logger.trace("RAW LLM STREAM CHUNK (Pure LLM): {}", chunk);
                        chunkConsumer.accept(chunk);
                    })
                    .doOnError(error -> {
                        logger.error("Error during LLM stream (Pure LLM): {}", error.getMessage(), error);
                        if (statusListener != null) statusListener.onStatus("LLM stream error: " + error.getMessage(), 100);
                    })
                    .doOnComplete(() -> {
                        logger.info("[{}] LLM (Pure Stream) call finished", Instant.now());
                        if (statusListener != null) statusListener.onStatus("LLM stream complete", 100);
                    })
                    .subscribe();

            } else if (request.isIncludeLlmFallback()) { // RAG + LLM Fallback
                if (statusListener != null) statusListener.onStatus("Querying database for relevant context (stream)", 20);
                logger.debug("Checking for context (threshold 0.7) for stream message: {}", request.getMessage());
                Query query = new Query(request.getMessage());
                List<Document> docs;
                try {
                    logger.info("[{}] Vector DB (RAG+Fallback Stream) call started", Instant.now());
                    docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), timeoutExecutor)
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

                String contextText = null;
                if (docs != null && !docs.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Document doc : docs) {
                        sb.append(doc.getFormattedContent()).append("\n");
                    }
                    contextText = sb.toString().trim();
                }

                String llmPrompt;
                if (contextText != null && !contextText.isEmpty()) {
                    llmPrompt = "Context:\n" + contextText + "\n\nUser Question:\n" + request.getMessage();
                    if (statusListener != null) statusListener.onStatus("Calling LLM with context (stream)", 70);
                } else {
                    llmPrompt = request.getMessage();
                    if (statusListener != null) statusListener.onStatus("Calling LLM without context (stream)", 70);
                }
                logger.debug("LLM Prompt (RAG+Fallback Stream Mode): User: [{}]", llmPrompt);
                logger.info("[{}] LLM (RAG+Fallback Stream) call started", Instant.now());

                chatClient.prompt()
                    .user(llmPrompt)
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        logger.trace("RAW LLM STREAM CHUNK (RAG+Fallback): {}", chunk);
                        chunkConsumer.accept(chunk);
                    })
                    .doOnError(error -> {
                        logger.error("Error during LLM stream (RAG+Fallback): {}", error.getMessage(), error);
                        if (statusListener != null) statusListener.onStatus("LLM stream error: " + error.getMessage(), 100);
                    })
                    .doOnComplete(() -> {
                        logger.info("[{}] LLM (RAG+Fallback Stream) call finished", Instant.now());
                        if (statusListener != null) statusListener.onStatus("LLM stream complete", 100);
                    })
                    .subscribe();

            } else if (request.isRawRag()) {
                logger.warn("Streaming for Raw RAG mode is not applicable for LLM response generation. Returning placeholder.");
                if (statusListener != null) statusListener.onStatus("Raw RAG mode selected; no LLM stream.", 100);
                chunkConsumer.accept("Raw RAG mode returns direct document content, not a streamed LLM response. Use non-streaming endpoint for Raw RAG.");
                // Simulate completion for SSE handler
                 if (statusListener != null) statusListener.onStatus("LLM stream complete", 100); 

            } else { // RAG Only
                logger.debug("RAG Only mode for stream message: {}", request.getMessage());
                if (statusListener != null) statusListener.onStatus("Sending Prompt to LLM for Pre-Processing (stream)", 15);
                String originalPrompt = request.getMessage();
                String cleanedPrompt;
                try {
                    logger.info("[{}] LLM (Query Cleaning - RAG ONLY STREAM) call starting via CompletableFuture", Instant.now());
                    cleanedPrompt = CompletableFuture.supplyAsync(() -> cleanQueryWithLlm(originalPrompt, "RAG ONLY STREAM"), timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // Apply the same timeout
                    logger.info("[{}] LLM (Query Cleaning - RAG ONLY STREAM) call finished via CompletableFuture. Cleaned prompt: '{}'", Instant.now(), cleanedPrompt);
                } catch (TimeoutException te) {
                    logger.error("LLM Pre-Processing (RAG Only Stream) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("LLM Pre-Processing (RAG Only Stream) call timed out");
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("LLM Pre-Processing (RAG Only Stream) call failed: {}", e.getMessage(), e);
                    // Check if the cause was a RuntimeException from cleanQueryWithLlm itself
                    if (e.getCause() instanceof RuntimeException) {
                        throw (RuntimeException) e.getCause();
                    }
                    throw new RuntimeException("LLM Pre-Processing (RAG Only Stream) call failed: " + e.getMessage());
                }
                if (statusListener != null) statusListener.onStatus("Pre-Processed Query returned (stream)", 18);
                if (statusListener != null) statusListener.onStatus("Querying vector DB for relevant context (stream)", 20);

                Query query = new Query(cleanedPrompt);
                List<Document> docs;
                try {
                    logger.info("[{}] Vector DB (RAG Only Stream) call started", Instant.now());
                    docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), timeoutExecutor)
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

                String contextText = null;
                if (docs != null && !docs.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Document doc : docs) {
                        sb.append(doc.getFormattedContent()).append("\n");
                    }
                    contextText = sb.toString().trim();
                }

                if (contextText != null && !contextText.isEmpty()) {
                    if (statusListener != null) statusListener.onStatus("Calling LLM to summarize context (stream)", 70);
                    String llmSummaryPrompt = "Given the following context, answer the user's question as best as possible. Only use the provided context, do not invent new information.\nContext:\n" + contextText + "\n\nUser Question:\n" + cleanedPrompt;
                    logger.info("=== FULL LLM PROMPT (RAG ONLY STREAM) [first 500 chars] ===\n{}\n===============================", llmSummaryPrompt.substring(0, Math.min(500, llmSummaryPrompt.length())));
                    logger.trace("LLM Prompt (RAG Only - Summarization Stream): User: [{}]", llmSummaryPrompt);
                    logger.info("[{}] LLM (RAG Only Stream) call started", Instant.now());

                    chatClient.prompt()
                        .user(llmSummaryPrompt)
                        .stream()
                        .content()
                        .doOnNext(chunk -> {
                            logger.trace("RAW LLM STREAM CHUNK (RAG Only): {}", chunk);
                            chunkConsumer.accept(chunk);
                        })
                        .doOnError(error -> {
                            logger.error("Error during LLM stream (RAG Only): {}", error.getMessage(), error);
                            if (statusListener != null) statusListener.onStatus("LLM stream error: " + error.getMessage(), 100);
                        })
                        .doOnComplete(() -> {
                            logger.info("[{}] LLM (RAG Only Stream) call finished", Instant.now());
                            if (statusListener != null) statusListener.onStatus("COMPLETED", 100);
                        })
                        .subscribe();
                } else {
                    // No context found, send a message and complete the stream
                    logger.info("No context found for RAG Only stream. Completing.");
                    if (statusListener != null) statusListener.onStatus("No context found, stream complete", 90);
                    chunkConsumer.accept("No relevant context was found to answer your question.\n\nSource: 0 (no context)");
                    if (statusListener != null) statusListener.onStatus("COMPLETED", 100);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing stream message: " + request.getMessage(), e);
            if (statusListener != null) statusListener.onStatus("Stream processing error: " + e.getMessage(), 100);
            try {
                // Ensure some kind of terminal message reaches the client even on outer error
                chunkConsumer.accept("Error during streaming setup: " + e.getMessage());
            } catch (Exception ex) {
                logger.error("Failed to send error chunk to consumer after outer error", ex);
            }
        } finally {
            timeoutExecutor.shutdown();
            try {
                if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    timeoutExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                timeoutExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }


    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment springEnv; // For debug logging of base-url

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    
    /**
     * The ChatClient used to interact with the LLM and RAG advisor.
     */
    private final ChatClient chatClient;
    /**
     * The VectorStore used for semantic retrieval of context documents.
     */

    private final VectorStoreDocumentRetriever documentRetriever;

    /**
     * Constructs a RagService with the given ChatClient and VectorStore.
     * Initializes a custom RetrievalAugmentationAdvisor with a similarity threshold.
     *
     * @param chatClient   the chat client for LLM and advisor interactions
     * @param vectorStore  the vector store for context retrieval
     */
    public RagService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.documentRetriever = VectorStoreDocumentRetriever.builder()
            .similarityThreshold(0.7)
            .vectorStore(vectorStore)
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
    public interface RagStatusListener {
    void onStatus(String statusMessage, int progress);
}

private static final int TIMEOUT_SECONDS = 180;

public ChatResponse chat(ChatRequest request, RagStatusListener statusListener) {
        ExecutorService timeoutExecutor = Executors.newSingleThreadExecutor();
        String answer;
        String source = "RAG";
        String responseMode = determineResponseMode(request);
        
        // Log the incoming request and selected response mode
        logger.info("Processing request - Mode: {}, Message: {}", responseMode, request.getMessage());
        
        try {
            if (statusListener != null) statusListener.onStatus("Received request", 10);
            logger.info("[{}] Job started for message: {}", Instant.now(), request.getMessage());
            // If Raw RAG mode is enabled, return concatenated DB results without LLM summarization
            if (request.isRawRag()) {
                if (statusListener != null) statusListener.onStatus("Sending Prompt to LLM for Pre-Processing", 15);
                String originalPrompt = request.getMessage();
                String cleanedPrompt = cleanQueryWithLlm(originalPrompt, "RAW RAG");
                if (statusListener != null) statusListener.onStatus("Pre-Processed Query returned", 18);
                logger.info("[Raw RAG] Original user prompt: {}", originalPrompt);
                logger.info("[Raw RAG] Cleaned/rephrased prompt: {}", cleanedPrompt);
                if (statusListener != null) statusListener.onStatus("Querying vector DB for raw context", 20);
                Query query = new Query(cleanedPrompt);
                List<Document> docs = null;
                try {
                    logger.info("[{}] Vector DB (Raw RAG) call started", Instant.now());
                    docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("[{}] Vector DB (Raw RAG) call finished", Instant.now());
                } catch (TimeoutException te) {
                    logger.error("Vector DB (Raw RAG) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("Vector DB (Raw RAG) call timed out");
                }
                logger.info("Vector DB query (Raw RAG) returned {} documents.", docs != null ? docs.size() : 0);
                StringBuilder rawText = new StringBuilder();
                if (docs != null && !docs.isEmpty()) {
                    for (int i = 0; i < docs.size(); i++) {
                        String snippet = docs.get(i).getFormattedContent();
                        logger.trace("Raw RAG doc[{}] snippet [first 200 chars]: {}", i, snippet.substring(0, Math.min(snippet.length(), 200)));
                        rawText.append(snippet).append("\n");
                    }
                }
                String result = rawText.toString().trim();
                if (result.isEmpty()) {
                    answer = "No relevant context was found to answer your question.\n\nSource: RAW_RAG (no context)";
                } else {
                    answer = "--- RAW RAG MODE: Concatenated DB Results ---\n" + result + "\n\nSource: RAW_RAG (raw DB text, no LLM)";
                }
                source = "RAW_RAG";
                logger.debug("Raw RAG response for message '{}': {}", cleanedPrompt, answer);
            } else if (request.isUsePureLlm()) {
                if (statusListener != null) statusListener.onStatus("Calling LLM (no RAG)", 30);
                logger.debug("Using Pure LLM mode for message: {}", request.getMessage());
                Prompt pureLlmPrompt = new Prompt(request.getMessage());
                logger.debug("LLM Prompt (Pure LLM Mode): User: [{}]", request.getMessage());
                String llmAnswer = null;
                try {
                    // DEBUG: Log the current OpenAI base-url property before LLM call
                    if (springEnv != null) {
                        String baseUrl = springEnv.getProperty("spring.ai.openai.base-url");
                        logger.debug("[DEBUG] spring.ai.openai.base-url before LLM call: {}", baseUrl);
                    }
                    logger.info("[{}] LLM (Pure) call started", Instant.now());
                    llmAnswer = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                        .user(request.getMessage())
                        .call()
                        .content(), timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("RAW LLM RESPONSE (Pure LLM): {}", llmAnswer);
                    logger.info("[{}] LLM (Pure) call finished", Instant.now());
                } catch (TimeoutException te) {
                    logger.error("LLM (Pure) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("LLM (Pure) call timed out");
                }
                if (statusListener != null) statusListener.onStatus("LLM response received", 90);
                answer = "LLM Answer:\n" + llmAnswer + "\n\nSource: 0+2 (LLM only)";
                source = "LLM";
                logger.debug("Pure LLM response for message '{}': {}", request.getMessage(), answer);
            } else if (request.isIncludeLlmFallback()) {
                if (statusListener != null) statusListener.onStatus("Querying database for relevant context", 20);
                // RAG + LLM Fallback: add context directly to prompt, no summarization
                logger.debug("Checking for context (threshold 0.7) for message: {}", request.getMessage());
                Query query = new Query(request.getMessage());
                List<Document> docs = null;
                try {
                    logger.info("[{}] Vector DB (RAG+Fallback) call started", Instant.now());
                    docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("[{}] Vector DB (RAG+Fallback) call finished", Instant.now());
                } catch (TimeoutException te) {
                    logger.error("Vector DB (RAG+Fallback) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("Vector DB (RAG+Fallback) call timed out");
                }
                logger.info("Vector DB query (RAG+Fallback) returned {} documents.", docs.size());
                if (!docs.isEmpty()) {
                    logger.debug("First retrieved document snippet (RAG+Fallback): {}", docs.get(0).getFormattedContent().substring(0, Math.min(docs.get(0).getFormattedContent().length(), 200)));
                }
                String contextText = null;
                if (docs != null && !docs.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Document doc : docs) {
                        sb.append(doc.getFormattedContent()).append("\n"); // Use getContent() for proper text extraction
                    }
                    contextText = sb.toString().trim();
                }
                String llmPrompt;
                String sourceCode;
                if (contextText != null && !contextText.isEmpty()) {
                    llmPrompt = "Context:\n" + contextText + "\n\nUser Question:\n" + request.getMessage();
                    sourceCode = "1+2 (RAG context + LLM)";
                } else {
                    llmPrompt = request.getMessage();
                    sourceCode = "0+2 (LLM only)";
                }
                // Log the actual prompt being sent to the LLM
                logger.debug("LLM Prompt (RAG+Fallback Mode): User: [{}]", llmPrompt);
                if (statusListener != null) statusListener.onStatus("Calling LLM with prompt", 70);
                String llmAnswer = null;
                try {
                    logger.info("[{}] LLM (RAG+Fallback) call started", Instant.now());
                    llmAnswer = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                        .user(llmPrompt)
                        .call()
                        .content(), timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("RAW LLM RESPONSE (RAG+Fallback): {}", llmAnswer);
                    logger.info("[{}] LLM (RAG+Fallback) call finished", Instant.now());
                } catch (TimeoutException te) {
                    logger.error("LLM (RAG+Fallback) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("LLM (RAG+Fallback) call timed out");
                }
                if (statusListener != null) statusListener.onStatus("LLM response received", 90);
                StringBuilder responseSb = new StringBuilder();
                if (contextText != null && !contextText.isEmpty()) {
                    responseSb.append("(RAG context was added to the LLM prompt.)\n\n");
                }
                responseSb.append(llmAnswer);
                responseSb.append("\n\nSource: ").append(sourceCode);
                answer = responseSb.toString();
                source = "LLM";
                logger.debug("LLM response for message '{}' with context: {}: {}", request.getMessage(), contextText != null, answer);
            } else {
                // RAG Only: pre-process query via LLM, then use for vector search
                logger.debug("RAG Only mode for message: {}", request.getMessage());
                if (statusListener != null) statusListener.onStatus("Sending Prompt to LLM for Pre-Processing", 15);
                String originalPrompt = request.getMessage();
                String cleanedPrompt = cleanQueryWithLlm(originalPrompt, "RAG ONLY");
                if (statusListener != null) statusListener.onStatus("Pre-Processed Query returned", 18);
                if (statusListener != null) statusListener.onStatus("Querying vector DB for relevant context", 20);
                Query query = new Query(cleanedPrompt);
                List<Document> docs = null;
                try {
                    if (statusListener != null) statusListener.onStatus("Vector DB (RAG Only) call started", 22);
                    logger.info("[{}] Vector DB (RAG Only) call started", Instant.now());
                    docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    logger.info("[{}] Vector DB (RAG Only) call finished", Instant.now());
                    if (statusListener != null) statusListener.onStatus("Vector DB (RAG Only) call finished", 25);
                } catch (TimeoutException te) {
                    logger.error("Vector DB (RAG Only) call timed out after {}s", TIMEOUT_SECONDS);
                    throw new RuntimeException("Vector DB (RAG Only) call timed out");
                }
                logger.info("Vector DB query (RAG Only) returned {} documents.", docs.size());
                if (!docs.isEmpty()) {
                    for (int i = 0; i < docs.size(); i++) {
                        String snippet = docs.get(i).getFormattedContent();
                        logger.trace("RAG Only doc[{}] snippet [first 200 chars]: {}", i, snippet.substring(0, Math.min(snippet.length(), 200)));
                    }
                }
                if (statusListener != null) statusListener.onStatus(
                    "Vector DB query complete: " + (docs != null ? docs.size() : 0) + " results", 40
                );
                String contextText = null;
                if (docs != null && !docs.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Document doc : docs) {
                        String content = doc.getFormattedContent();
                        logger.trace("Doc content for contextText [first 200 chars]: [{}]", content != null ? content.substring(0, Math.min(200, content.length())) : "");
                        sb.append(content).append("\n");
                    }
                    contextText = sb.toString().trim();
                    logger.info("Final contextText for prompt [first 500 chars]: [{}]", contextText != null ? contextText.substring(0, Math.min(500, contextText.length())) : "");
                }
                if (contextText != null && !contextText.isEmpty()) {
                    // Use LLM to summarize context as the answer to the user's question
                    if (statusListener != null) statusListener.onStatus("Calling LLM to summarize context", 70);
                    String llmSummaryPrompt = "Given the following context, answer the user's question as best as possible. Only use the provided context, do not invent new information.\nContext:\n" + contextText + "\n\nUser Question:\n" + request.getMessage();
                    logger.info("=== FULL LLM PROMPT (RAG ONLY) [first 500 chars] ===\n{}\n===============================", llmSummaryPrompt.substring(0, Math.min(500, llmSummaryPrompt.length())));
                    logger.trace("LLM Prompt (RAG Only - Summarization): User: [{}]", llmSummaryPrompt);
                    String llmSummary = null;
                    try {
                        logger.info("[{}] LLM (RAG Only) call started", Instant.now());
                        llmSummary = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                            .user(llmSummaryPrompt)
                            .call()
                            .content(), timeoutExecutor)
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        logger.info("RAW LLM RESPONSE (RAG Only Summary): {}", llmSummary);
                        logger.info("[{}] LLM (RAG Only) call finished", Instant.now());
                    } catch (TimeoutException te) {
                        logger.error("LLM (RAG Only) call timed out after {}s", TIMEOUT_SECONDS);
                        throw new RuntimeException("LLM (RAG Only) call timed out");
                    }
                    if (statusListener != null) statusListener.onStatus("LLM response received", 90);
                    answer = "--- RAG LLM Summary ---\n" + llmSummary + "\n\nSource: 1 (RAG context summarized by LLM)";
                    source = "RAG";
                } else {
                    answer = "No relevant context was found to answer your question.\n\nSource: 0 (no context)";
                    source = "RAG";
                }
                logger.debug("RAG Only response for message '{}': {}", request.getMessage(), answer);
            }
            // Log the final response and source
            logger.info("Response generated - Source: {}, Mode: {}, Message: {}, Answer: {}", 
                source, responseMode, request.getMessage(), answer);
        } catch (Exception e) {
            answer = "An error occurred while processing your request.";
            source = "ERROR";
            if (statusListener != null) statusListener.onStatus("LLM call failed: " + e.getMessage(), 100);
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
     * Helper method to clean and rephrase user queries via LLM with a system prompt.
     * Logs both the original and cleaned prompts.
     */
    private String cleanQueryWithLlm(String originalPrompt, String modeTag) {
        String systemPrompt = "You are an AI assistant that serves as an expert query pre-processor for a technical knowledge base for users based on documents you are given.  Your task is to correct any spelling and grammatical errors in the following user query and rephrase it into a clear, unambiguous question. The output will be used to perform a vector search against the documentation. Provide only the corrected and rephrased query. Do not answer the question.";
        String cleanedPrompt = null;
        ExecutorService timeoutExecutor = Executors.newSingleThreadExecutor();
        try {
            logger.info("[{}] [{}] LLM (Query Cleaning) call started", Instant.now(), modeTag);
            cleanedPrompt = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                .system(systemPrompt)
                .user(originalPrompt)
                .call()
                .content(), timeoutExecutor)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            logger.info("RAW LLM RESPONSE (Query Cleaning - {}): {}", modeTag, cleanedPrompt);
            logger.info("[{}] [{}] LLM (Query Cleaning) call finished", Instant.now(), modeTag);
        } catch (TimeoutException te) {
            logger.error("LLM (Query Cleaning) call timed out after {}s [{}]", TIMEOUT_SECONDS, modeTag);
            throw new RuntimeException("LLM (Query Cleaning) call timed out");
        } catch (Exception e) {
            logger.error("LLM (Query Cleaning) failed [{}]: {}", modeTag, e.getMessage(), e);
            throw new RuntimeException("LLM (Query Cleaning) failed: " + e.getMessage(), e);
        }
        logger.info("[{}] Original user prompt: {}", modeTag, originalPrompt);
        logger.info("[{}] Cleaned/rephrased prompt: {}", modeTag, cleanedPrompt);
        return cleanedPrompt;
    }

}
