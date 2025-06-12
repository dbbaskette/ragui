package com.baskettecase.ragui.dto;

public class StreamEvent {

    private final String type;
    private final String content;

    public StreamEvent(String type, String content) {
        this.type = type;
        this.content = content;
    }

    public String getType() {
        return type;
    }

    public String getContent() {
        return content;
    }
}
