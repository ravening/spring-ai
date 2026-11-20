package com.example.bank.notify;

import java.util.List;

/** Wire types for the notification inbox. Mirrors the mobile client's lib/types.ts. */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * One event to persist. {@code dedupeKey} makes emission idempotent;
     * {@code chatMessage} is optional rich markdown appended to the conversation
     * (null for answer_ready, whose answer is already stored by BankChatService).
     */
    public record NotificationEvent(
            String userId,
            String conversationId,
            String type,
            String title,
            String body,
            String detail,
            String jobId,
            String dedupeKey,
            String chatMessage) {
    }

    public record NotificationDto(
            long id,
            String userId,
            String conversationId,
            String type,
            String title,
            String body,
            String detail,
            String jobId,
            boolean read,
            long createdAt) {
    }

    public record NotificationPage(long unreadCount, List<NotificationDto> items) {
    }

    public record UnreadCount(long count) {
    }
}
