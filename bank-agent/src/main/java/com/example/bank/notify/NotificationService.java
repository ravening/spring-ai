package com.example.bank.notify;

import java.util.Map;
import java.util.UUID;

import com.example.bank.chat.ConversationService;
import com.example.bank.notify.NotificationDtos.NotificationEvent;
import com.example.bank.push.PushService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Single emit path for every async event (job result, incoming transfer, answer
 * ready). Persists the authoritative row, optionally appends the rich chat message,
 * then fires a best-effort push wake-up. Notification delivery never breaks a
 * business action.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NotificationStore store;
    private final ConversationService conversations;
    private final PushService push;

    public NotificationService(NotificationStore store, ConversationService conversations, PushService push) {
        this.store = store;
        this.conversations = conversations;
        this.push = push;
    }

    /** Returns the inserted notification id, or Long.MIN_VALUE when deduped. */
    public long emit(NotificationEvent event) {
        String dedupeKey = event.dedupeKey() == null || event.dedupeKey().isBlank()
                ? "evt:" + UUID.randomUUID()
                : event.dedupeKey();
        long id = store.insert(event.userId(), event.conversationId(), event.type(), event.title(),
                event.body(), event.detail(), event.jobId(), dedupeKey, System.currentTimeMillis());
        if (id == Long.MIN_VALUE) {
            log.debug("Notification deduped by key {}", dedupeKey);
            return id;
        }

        // One authoritative record writes both the row and (optionally) the chat bubble.
        if (event.chatMessage() != null && !event.chatMessage().isBlank()) {
            try {
                conversations.addAssistantMessage(event.conversationId(), event.chatMessage());
            } catch (Exception ex) {
                log.warn("Could not append notification chat message: {}", ex.getMessage());
            }
        }

        try {
            push.sendWakeUp(event.userId(), id, event.conversationId(), "/conversations/" + event.conversationId());
        } catch (Exception ex) {
            log.warn("Push wake-up failed (ignored): {}", ex.getMessage());
        }
        return id;
    }

    /** Recipient's most recently updated chat, else the lazy SYSTEM notification chat. */
    public String recipientConversation(String userId) {
        return conversations.mostRecentConversationFor(userId)
                .orElseGet(() -> conversations.systemConversationFor(userId));
    }

    /** Serialises a notification {@code detail} object as JSON; never throws. */
    public static String json(Map<String, Object> detail) {
        try {
            return MAPPER.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
