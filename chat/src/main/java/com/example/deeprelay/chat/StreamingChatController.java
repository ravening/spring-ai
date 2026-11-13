package com.example.deeprelay.chat;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class StreamingChatController {

    private final ChatClient chatClient;

    public StreamingChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam(defaultValue = "Hello, DeepRelay!") String message,
            @RequestParam(defaultValue = "default") String conversationId,
            HttpServletResponse response) {
        prepareStreamingResponse(response);
        return streamContent(message, conversationId);
    }

    @GetMapping(value = "/stream/text", produces = "text/plain;charset=UTF-8")
    public Flux<String> streamText(@RequestParam(defaultValue = "Hello, DeepRelay!") String message,
            @RequestParam(defaultValue = "default") String conversationId,
            HttpServletResponse response) {
        prepareStreamingResponse(response);
        return streamContent(message, conversationId);
    }

    private Flux<String> streamContent(String message, String conversationId) {
        return this.chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    private static void prepareStreamingResponse(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
    }
}
