package com.example.deeprelay.chat;

import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.deeprelay.rag.ConversationAwareRagAdvisor;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    private final ConversationAwareRagAdvisor ragAdvisor;

    public ChatController(ChatClient chatClient, ConversationAwareRagAdvisor ragAdvisor) {
        this.chatClient = chatClient;
        this.ragAdvisor = ragAdvisor;
    }

    @GetMapping
    public ChatResponse chat(@RequestParam(defaultValue = "Hello, DeepRelay!") String message,
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestParam(defaultValue = "false") boolean rag) {
        String id = resolveConversationId(conversationId);
        String response = prompt(message, id, rag).call().content();
        return new ChatResponse(response, id);
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String id = resolveConversationId(request.conversationId());
        String response = prompt(request.message(), id, Boolean.TRUE.equals(request.rag())).call().content();
        return new ChatResponse(response, id);
    }

    @PostMapping(value = "/stream", produces = "text/plain;charset=UTF-8")
    public Flux<String> stream(@RequestBody ChatRequest request, HttpServletResponse response) {
        String id = resolveConversationId(request.conversationId());
        response.setHeader("X-Conversation-Id", id);
        return prompt(request.message(), id, Boolean.TRUE.equals(request.rag())).stream().content();
    }

    private ChatClient.ChatClientRequestSpec prompt(String message, String conversationId, boolean rag) {
        String safeMessage = PromptSafety.validatedMessage(message);
        ChatClient.ChatClientRequestSpec spec = this.chatClient.prompt().user(safeMessage);
        if (rag) {
            spec = spec.advisors(this.ragAdvisor);
        }
        return spec.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));
    }

    private static String resolveConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return UUID.randomUUID().toString();
        }
        if (conversationId.length() > 36) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId must be at most 36 characters");
        }
        return conversationId;
    }

    public record ChatRequest(String message, String conversationId, Boolean rag) {
    }

    public record ChatResponse(String response, String conversationId) {
    }
}
