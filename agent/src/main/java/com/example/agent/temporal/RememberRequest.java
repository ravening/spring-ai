package com.example.agent.temporal;

public record RememberRequest(
        String conversationId,
        String task,
        String answer,
        String runId) {
}
