package com.example.bank.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.bank.core.AgentEvent;
import com.example.bank.core.AgentTrace;
import com.example.bank.core.ToolCallRecord;

/** One in-flight (or finished) assistant run for a conversation. */
public final class RunRecord {

    public enum Status {
        RUNNING, COMPLETED, FAILED
    }

    private final String runId = UUID.randomUUID().toString();
    private final String conversationId;
    private final String userId;
    private final String message;
    private final AgentTrace trace;
    private final Instant createdAt = Instant.now();

    private volatile Status status = Status.RUNNING;
    private volatile String answer;
    private volatile String error;
    private volatile Instant finishedAt;

    public RunRecord(String conversationId, String userId, String message) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.message = message;
        this.trace = new AgentTrace(conversationId);
    }

    public String runId() {
        return runId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String userId() {
        return userId;
    }

    public String message() {
        return message;
    }

    public AgentTrace trace() {
        return trace;
    }

    public Status status() {
        return status;
    }

    public void complete(String answer) {
        this.answer = answer;
        this.status = Status.COMPLETED;
        this.finishedAt = Instant.now();
    }

    public void fail(String error) {
        this.error = error;
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
    }

    public RunStatus snapshot() {
        return new RunStatus(runId, conversationId, userId, status.name(), answer, error,
                createdAt, finishedAt, trace.events(), trace.toolCalls());
    }

    public record RunStatus(
            String runId,
            String conversationId,
            String userId,
            String status,
            String answer,
            String error,
            Instant createdAt,
            Instant finishedAt,
            List<AgentEvent> events,
            List<ToolCallRecord> toolCalls) {
    }
}
