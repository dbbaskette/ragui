package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import com.baskettecase.ragui.service.RagService;
import com.baskettecase.ragui.service.AppStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final RagService ragService;
    private final AppStatus appStatus;

    @Autowired
    public ChatController(RagService ragService, AppStatus appStatus) {
        this.ragService = ragService;
        this.appStatus = appStatus;
    }

    @Autowired
    private VectorStore vectorStore;

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        logger.info("Received chat request: {}", request.getMessage());
        
        try {
            ChatResponse response = ragService.chat(request, (status, progress) -> appStatus.setStatus(status));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error processing chat request", e);
            return ResponseEntity.status(500).body(
                new ChatResponse.Builder()
                    .answer("An error occurred while processing your request.")
                    .source("ERROR")
                    .build()
            );
        }
    }

    /**
     * Debug endpoint to test vector store directly with configurable parameters
     */
    @GetMapping("/debug/vector-search")
    public ResponseEntity<Map<String, Object>> debugVectorSearch(
            @RequestParam(defaultValue = "test") String query,
            @RequestParam(defaultValue = "0.0") double threshold,
            @RequestParam(defaultValue = "10") int topK) {
        
        logger.info("DEBUG: Testing vector search with query='{}', threshold={}, topK={}", query, threshold, topK);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .similarityThreshold(threshold)
                .topK(topK)
                .build();
            
            List<Document> docs = vectorStore.similaritySearch(searchRequest);
            
            result.put("query", query);
            result.put("threshold", threshold);
            result.put("topK", topK);
            result.put("documentsFound", docs.size());
            result.put("vectorStoreName", vectorStore.getName());
            
            if (!docs.isEmpty()) {
                result.put("firstDocumentLength", docs.get(0).getFormattedContent().length());
                result.put("firstDocumentMetadata", docs.get(0).getMetadata());
                result.put("firstDocumentPreview", docs.get(0).getFormattedContent().substring(0, 
                    Math.min(200, docs.get(0).getFormattedContent().length())));
            }
            
            logger.info("DEBUG: Vector search returned {} documents", docs.size());
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("DEBUG: Vector search failed", e);
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Debug endpoint to test with common queries
     */
    @GetMapping("/debug/test-queries")
    public ResponseEntity<Map<String, Object>> testCommonQueries() {
        Map<String, Object> results = new HashMap<>();
        
        String[] testQueries = {
            "platform engineering",
            "spring boot", 
            "kubernetes",
            "devops",
            "microservices",
            "test"
        };
        
        for (String query : testQueries) {
            try {
                SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .similarityThreshold(0.0) // Very permissive
                    .topK(5)
                    .build();
                
                List<Document> docs = vectorStore.similaritySearch(searchRequest);
                
                Map<String, Object> queryResult = new HashMap<>();
                queryResult.put("documentsFound", docs.size());
                if (!docs.isEmpty()) {
                    queryResult.put("firstDocMetadata", docs.get(0).getMetadata());
                }
                
                results.put(query, queryResult);
                
            } catch (Exception e) {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", e.getMessage());
                results.put(query, errorResult);
            }
        }
        
        return ResponseEntity.ok(results);
    }
}
