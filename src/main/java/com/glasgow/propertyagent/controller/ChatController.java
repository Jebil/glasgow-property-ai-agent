package com.glasgow.propertyagent.controller;

import com.glasgow.propertyagent.service.ai.ChatAssistantService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * REST entry point for the AI chat assistant. The browser posts the conversation plus the currently applied
 * filters and top ranked results; the reply may carry UI actions (e.g. apply_filters) for the frontend to run.
 * Supports both synchronous JSON (POST /api/chat) and live tool/status SSE streaming (POST /api/chat/stream).
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

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatAssistantService.ChatRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            try {
                emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("error", "messages are required"), MediaType.APPLICATION_JSON));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }

        Thread.ofVirtual().name("chat-sse-worker").start(() -> {
            try {
                chatService.chat(request, (eventType, payload) -> {
                    try {
                        emitter.send(SseEmitter.event()
                            .name(eventType)
                            .data(payload, MediaType.APPLICATION_JSON));
                    } catch (Exception ignored) {}
                });
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("error", e.getMessage() != null ? e.getMessage() : "Chat stream failed"), MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
