package com.baskettecase.ragui.dto;

/**
 * ChatResponse represents a response to a chat request, including the answer and its source (RAG or LLM).
 */
public class ChatResponse {
    private String answer;
    /**
     * Indicates the source of the answer: "RAG" (vector store context) or "LLM" (language model fallback).
     */
    private String source;

    public ChatResponse() {}
    public ChatResponse(String answer, String source) {
        this.answer = answer;
        this.source = source;
    }

    public ChatResponse(String answer) {
        this(answer, null);
    }

    public String getAnswer() {
        return answer;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    @Override
    public String toString() {
        return "ChatResponse{" +
                "answer='" + answer + '\'' +
                ", source='" + source + '\'' +
                '}';
    }
}
