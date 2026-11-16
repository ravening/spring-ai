package com.example.agent.core;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

public final class ConversationId {

    private ConversationId() {
    }

    public static String resolve(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return UUID.randomUUID().toString();
        }
        if (conversationId.length() > 36) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId must be at most 36 characters");
        }
        return conversationId;
    }
}
