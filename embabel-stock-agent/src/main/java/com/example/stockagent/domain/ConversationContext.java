package com.example.stockagent.domain;

import java.util.List;

/** History and uploaded-document context bound into the Embabel blackboard. */
public record ConversationContext(
        String conversationId,
        List<ChatTurn> history,
        List<DocumentSnippet> documents
) {
    public static ConversationContext empty(String conversationId) {
        return new ConversationContext(conversationId, List.of(), List.of());
    }
}
