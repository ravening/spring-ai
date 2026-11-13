package com.example.deeprelay.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping
    public ChatResponse chat(@RequestParam(defaultValue = "Hello, DeepRelay!") String message,
            @RequestParam(defaultValue = "default") String conversationId) {
        String response = this.chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return new ChatResponse(response);
    }

    public record ChatResponse(String response) {
    }
}
