package com.example.deeprelay.chat;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ChatMemoryRepository chatMemoryRepository;

    private final ChatMemory chatMemory;

    public ConversationController(ChatMemoryRepository chatMemoryRepository, ChatMemory chatMemory) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemory = chatMemory;
    }

    @GetMapping
    public List<ConversationSummary> list() {
        return this.chatMemoryRepository.findConversationIds().stream()
                .map(this::summarize)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ConversationSummary::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @GetMapping("/{conversationId}")
    public ConversationDetail get(@PathVariable String conversationId) {
        List<Message> messages = this.chatMemoryRepository.findByConversationId(conversationId);
        if (messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return new ConversationDetail(conversationId, title(messages),
                messages.stream().map(ConversationMessage::from).toList());
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId) {
        this.chatMemory.clear(conversationId);
    }

    private ConversationSummary summarize(String conversationId) {
        List<Message> messages = this.chatMemoryRepository.findByConversationId(conversationId);
        if (messages.isEmpty()) {
            return null;
        }
        return new ConversationSummary(conversationId, title(messages), lastTimestamp(messages), messages.size());
    }

    private static String title(List<Message> messages) {
        return messages.stream()
                .filter(message -> message.getMessageType() == MessageType.USER)
                .map(Message::getText)
                .filter(Objects::nonNull)
                .map(text -> text.replaceAll("\\s+", " ").strip())
                .filter(text -> !text.isEmpty())
                .findFirst()
                .map(text -> text.length() > 70 ? text.substring(0, 70).strip() + "…" : text)
                .orElse("New chat");
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

    public record ConversationSummary(String id, String title, Instant updatedAt, int messageCount) {
    }

    public record ConversationDetail(String id, String title, List<ConversationMessage> messages) {
    }

    public record ConversationMessage(String role, String content, Instant createdAt) {

        static ConversationMessage from(Message message) {
            Object value = message.getMetadata().get(JdbcChatMemoryRepository.CONVERSATION_TS);
            return new ConversationMessage(message.getMessageType().name().toLowerCase(Locale.ROOT),
                    message.getText(), value instanceof Instant instant ? instant : null);
        }
    }
}
