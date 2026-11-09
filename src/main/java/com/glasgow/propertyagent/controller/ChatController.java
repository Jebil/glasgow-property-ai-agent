package com.glasgow.propertyagent.controller;

import com.glasgow.propertyagent.service.ai.ChatAssistantService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST entry point for the AI chat assistant. The browser posts the conversation plus the currently applied
 * filters and top ranked results; the reply may carry UI actions (e.g. apply_filters) for the frontend to run.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatAssistantService chatService;

    public ChatController(ChatAssistantService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody ChatAssistantService.ChatRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "messages are required"));
        }
        return ResponseEntity.ok(chatService.chat(request));
    }
}
