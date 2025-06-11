package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.dto.ChatRequest;
import com.baskettecase.ragui.dto.ChatResponse;
import com.baskettecase.ragui.service.RagService;
import com.baskettecase.ragui.service.AppStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final RagService ragService;
    private final AppStatus appStatus;

    @Autowired
    public ChatController(RagService ragService, AppStatus appStatus) {
        this.ragService = ragService;
        this.appStatus = appStatus;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return ragService.chat(request, (status, progress) -> appStatus.setStatus(status));
    }
}
