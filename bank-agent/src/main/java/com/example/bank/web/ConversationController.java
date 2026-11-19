package com.example.bank.web;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.example.bank.auth.AuthSupport;
import com.example.bank.agent.RunRegistry;
import jakarta.servlet.http.HttpServletRequest;
import com.example.bank.chat.ConversationService;
import com.example.bank.knowledge.DocumentService;
import com.example.bank.users.UserDirectory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatMemoryRepository chatMemoryRepository;
    private final DocumentService documentService;
    private final RunRegistry runRegistry;
    private final UserDirectory users;

    public ConversationController(ConversationService conversationService,
            ChatMemoryRepository chatMemoryRepository, DocumentService documentService,
            RunRegistry runRegistry, UserDirectory users) {
        this.conversationService = conversationService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.documentService = documentService;
        this.runRegistry = runRegistry;
        this.users = users;
    }

    @GetMapping
    public List<ConversationSummary> list(HttpServletRequest http, @RequestParam String userId) {
        userId = AuthSupport.currentUserId(http, userId);
        users.resolve(userId);
        return conversationService.listForUser(userId).stream()
                .map(this::summarize)
                .sorted(Comparator.comparing(ConversationSummary::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @PostMapping
    public ConversationSummary create(HttpServletRequest http, @RequestBody CreateConversationRequest request) {
        var user = users.resolve(AuthSupport.currentUserId(http, request.userId()));
        String id = java.util.UUID.randomUUID().toString();
        conversationService.create(user.id(), id);
        return summarize(conversationService.get(id));
    }

    @GetMapping("/{conversationId}")
    public ConversationDetail get(HttpServletRequest http, @PathVariable String conversationId,
            @RequestParam String userId) {
        userId = AuthSupport.currentUserId(http, userId);
        users.resolve(userId);
        conversationService.ensureOwnership(conversationId, userId);
        var row = conversationService.get(conversationId);
        List<Message> messages = conversationService.history(conversationId);
        String activeRunId = runRegistry.activeRunId(conversationId);
        return new ConversationDetail(row.id(), row.userId(), row.title(),
                activeRunId != null, activeRunId,
                messages.stream().map(ConversationMessage::from).toList(),
                documentService.listVisible(conversationId, userId));
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest http, @PathVariable String conversationId,
            @RequestParam String userId) {
        userId = AuthSupport.currentUserId(http, userId);
        users.resolve(userId);
        conversationService.ensureOwnership(conversationId, userId);
        if (runRegistry.isActive(conversationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Conversation is still processing; wait for the current answer before deleting.");
        }
        conversationService.delete(conversationId);
    }

    private ConversationSummary summarize(ConversationService.ConversationRow row) {
        List<Message> messages = chatMemoryRepository.findByConversationId(row.id());
        String activeRunId = runRegistry.activeRunId(row.id());
        return new ConversationSummary(row.id(), row.userId(), row.title(),
                lastTimestamp(messages), messages.size(), activeRunId != null, activeRunId);
    }

    private static Instant lastTimestamp(List<Message> messages) {
        Instant last = null;
        for (Message message : messages) {
            Object value = message.getMetadata().get(JdbcChatMemoryRepository.CONVERSATION_TS);
            if (value instanceof Instant instant && (last == null || instant.isAfter(last))) {
                last = instant;
            }
        }
        return last;
    }

    public record CreateConversationRequest(String userId) {
    }

    public record ConversationSummary(
            String id,
            String userId,
            String title,
            Instant updatedAt,
            int messageCount,
            boolean active,
            String activeRunId) {
    }

    public record ConversationDetail(
            String id,
            String userId,
            String title,
            boolean active,
            String activeRunId,
            List<ConversationMessage> messages,
            List<DocumentService.DocumentInfo> documents) {
    }

    public record ConversationMessage(String role, String content, Instant createdAt) {

        static ConversationMessage from(Message message) {
            Object value = message.getMetadata().get(JdbcChatMemoryRepository.CONVERSATION_TS);
            return new ConversationMessage(message.getMessageType().name().toLowerCase(Locale.ROOT),
                    message.getText(), value instanceof Instant instant ? instant : null);
        }
    }
}
