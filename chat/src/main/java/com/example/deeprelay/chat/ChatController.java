package com.example.deeprelay.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping
    public ChatResponse chat(@RequestParam(defaultValue = "Hello, DeepRelay!") String message) {
        String response = this.chatClient.prompt()
                .user(message)
                .call()
                .content();
        return new ChatResponse(response);
    }

    public record ChatResponse(String response) {
    }
}
