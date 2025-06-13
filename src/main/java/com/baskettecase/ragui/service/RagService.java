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

    public RagService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.documentRetriever = VectorStoreDocumentRetriever.builder()
            .similarityThreshold(0.7) // Default, can be overridden by request
            .vectorStore(vectorStore)
            .build();
        this.timeoutExecutor = Executors.newCachedThreadPool();
        logger.info("RagService initialized with custom VectorStoreDocumentRetriever and newCachedThreadPool for timeoutExecutor.");
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
                if (statusListener != null) statusListener.onStatus("Calling LLM (no RAG) for streaming", 30);
                logger.debug("Using Pure LLM mode for stream: {}", request.getMessage());
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
                    llmPrompt = "Context:\\n" + contextText + "\\n\\nUser Question:\\n" + request.getMessage();
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

            } else { // RAG Only (implicitly, as Raw RAG is handled by JobController directly for non-streaming)
                logger.debug("RAG Only mode for stream message: {}", request.getMessage());
                if (statusListener != null) statusListener.onStatus("Sending Prompt to LLM for Pre-Processing (stream)", 15);
                String originalPrompt = request.getMessage();
                String cleanedPrompt;
                try {
                    logger.info("[{}] LLM (Query Cleaning - RAG ONLY STREAM) call starting via CompletableFuture", Instant.now());
                    cleanedPrompt = CompletableFuture.supplyAsync(() -> cleanQueryWithLlm(originalPrompt, "RAG ONLY STREAM"), this.timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
                    if (statusListener != null) statusListener.onStatus("Calling LLM to summarize context (stream)", 70);
                    String llmSummaryPrompt = "Given the following context, answer the user's question as best as possible. Only use the provided context, do not invent new information.\\nContext:\\n" + contextText + "\\n\\nUser Question:\\n" + cleanedPrompt;
                    logger.info("LLM Prompt (RAG Only Stream) [first 500 chars]: {}", llmSummaryPrompt.substring(0, Math.min(500, llmSummaryPrompt.length())));
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
                    logger.info("No context found for RAG Only stream. Completing.");
                    if (statusListener != null) statusListener.onStatus("No context found, stream complete", 90);
                    chunkConsumer.accept("No relevant context was found to answer your question.\\n\\nSource: 0 (no context)");
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
                    .user(request.getMessage()).call().content(), this.timeoutExecutor)
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
                String llmPrompt = (contextText != null && !contextText.isEmpty()) 
                    ? "Context:\n" + contextText + "\n\nUser Question:\n" + request.getMessage()
                    : request.getMessage();
                String sourceCode = (contextText != null && !contextText.isEmpty()) ? "RAG context + LLM" : "LLM only (no context found)";
                
                if (statusListener != null) statusListener.onStatus("Calling LLM with prompt", 70);
                String llmAnswer = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                    .user(llmPrompt).call().content(), this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (statusListener != null) statusListener.onStatus("LLM response received", 90);
                answer = llmAnswer + "\n\nSource: " + sourceCode;
                source = "LLM_FALLBACK";

            } else { // RAG Only
                if (statusListener != null) statusListener.onStatus("Sending Prompt to LLM for Pre-Processing", 15);
                String cleanedPrompt = cleanQueryWithLlm(request.getMessage(), "RAG ONLY");
                if (statusListener != null) statusListener.onStatus("Querying vector DB for relevant context", 20);
                Query query = new Query(cleanedPrompt);
                List<Document> docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), this.timeoutExecutor)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (statusListener != null) statusListener.onStatus("Vector DB query complete: " + docs.size() + " results", 40);
                String contextText = formatDocumentsToContext(docs);
                if (contextText != null && !contextText.isEmpty()) {
                    if (statusListener != null) statusListener.onStatus("Calling LLM to summarize context", 70);
                    String llmSummaryPrompt = "Given the following context, answer the user's question...[prompt]...";
                    String llmSummary = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                        .user(llmSummaryPrompt).call().content(), this.timeoutExecutor)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (statusListener != null) statusListener.onStatus("LLM response received", 90);
                    answer = llmSummary + "\n\nSource: RAG context summarized by LLM";
                    source = "RAG";
                } else {
                    answer = "No relevant context was found to answer your question.\n\nSource: RAG (no context)";
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
            String cleanedPrompt = cleanQueryWithLlm(originalPrompt, "RAW RAG");
            Query query = new Query(cleanedPrompt);
            List<Document> docs = CompletableFuture.supplyAsync(() -> documentRetriever.retrieve(query), this.timeoutExecutor)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
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
                String answer = "No relevant context was found to answer your question.";
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
    
    private String cleanQueryWithLlm(String originalPrompt, String modeTag) {
        String systemPrompt = "You are an AI assistant that serves as an expert query pre-processor for a technical knowledge base for users based on documents you are given.  Your task is to correct any spelling and grammatical errors in the following user query and rephrase it into a clear, unambiguous question. The output will be used to perform a vector search against the documentation. Provide only the corrected and rephrased query. Do not answer the question.";
        String cleanedPrompt;
        try {
            logger.info("[{}] [{}] LLM (Query Cleaning) call started", Instant.now(), modeTag);
            cleanedPrompt = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                .system(systemPrompt)
                .user(originalPrompt)
                .call()
                .content(), this.timeoutExecutor) // Ensure this uses this.timeoutExecutor
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            logger.info("RAW LLM RESPONSE (Query Cleaning - {}): {}", modeTag, cleanedPrompt);
            logger.info("[{}] [{}] LLM (Query Cleaning) call finished", Instant.now(), modeTag);
        } catch (TimeoutException te) {
            logger.error("LLM (Query Cleaning) call timed out after {}s [{}]", TIMEOUT_SECONDS, modeTag, te);
            throw new RuntimeException("LLM (Query Cleaning) call timed out for " + modeTag, te);
        } catch (Exception e) {
            logger.error("LLM (Query Cleaning) failed [{}] for prompt '{}': {}", modeTag, originalPrompt, e.getMessage(), e);
            throw new RuntimeException("LLM (Query Cleaning) failed for " + modeTag + ": " + e.getMessage(), e);
        }
        logger.info("[{}] Original user prompt: {}", modeTag, originalPrompt);
        logger.info("[{}] Cleaned/rephrased prompt: {}", modeTag, cleanedPrompt);
        return cleanedPrompt;
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