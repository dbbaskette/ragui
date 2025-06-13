package com.baskettecase.ragui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * ChatResponse represents a response to a chat request, including the answer and its source (RAG or LLM).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {
    private String answer;
    /**
     * Indicates the source of the answer: "RAG" (vector store context) or "LLM" (language model fallback).
     */
    private String source;
    private List<String> bubbles;

    // Private constructor for builder
    private ChatResponse(Builder builder) {
        this.answer = builder.answer;
        this.source = builder.source;
        this.bubbles = builder.bubbles;
    }

    // Getters
    public String getAnswer() { return answer; }
    public String getSource() { return source; }
    public List<String> getBubbles() { return bubbles; }

    // Builder class
    public static class Builder {
        private String answer;
        private String source;
        private List<String> bubbles;

        public Builder answer(String answer) {
            this.answer = answer;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder bubbles(List<String> bubbles) {
            this.bubbles = bubbles;
            return this;
        }

        public ChatResponse build() {
            return new ChatResponse(this);
        }
    }

    @Override
    public String toString() {
        return "ChatResponse{" +
                "answer='" + answer + '\'' +
                ", source='" + source + '\'' +
                ", bubbles=" + bubbles +
                '}';
    }
}
