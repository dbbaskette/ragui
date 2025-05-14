package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import com.baskettecase.ragui.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final RagService ragService;

    @Autowired
    public ChatController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return ragService.chat(request);
    }
}
