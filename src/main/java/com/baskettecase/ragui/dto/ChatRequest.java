package com.baskettecase.ragui.dto;

/**
 * ChatRequest represents a chat request from the user.
 *
 * The request can be processed in three modes:
 * 1. RAG only (default) - Only uses the vector store for context
 * 2. RAG with LLM fallback - Uses vector store first, falls back to LLM if needed
 * 3. Pure LLM - Bypasses the vector store and uses only the LLM
 */
public class ChatRequest {
    private String message;
    
    /**
     * If true, the backend will fall back to a pure LLM response if RAG cannot answer with relevant context.
     * This allows users to choose between RAG-only and RAG+LLM fallback modes from the UI.
     */
    private boolean includeLlmFallback;
    
    /**
     * If true, the backend will bypass the RAG system entirely and use only the LLM.
     * This takes precedence over includeLlmFallback.
     */
    private boolean usePureLlm;

    public boolean isIncludeLlmFallback() { return includeLlmFallback; }
    public void setIncludeLlmFallback(boolean includeLlmFallback) { this.includeLlmFallback = includeLlmFallback; }
    
    public boolean isUsePureLlm() { return usePureLlm; }
    public void setUsePureLlm(boolean usePureLlm) { this.usePureLlm = usePureLlm; }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
