package com.example.agent.core;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AgentTrace {

    public static final String CONTEXT_KEY = "agentTrace";

    private final String conversationId;
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
    private final List<ToolCallRecord> toolCalls = new CopyOnWriteArrayList<>();
    private volatile String activeAgent = "assistant";

    public AgentTrace(String conversationId) {
        this.conversationId = conversationId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String activeAgent() {
        return activeAgent;
    }

    public void activeAgent(String agent) {
        this.activeAgent = agent;
    }

    public void event(String type, String message) {
        event(type, activeAgent, message, null, null);
    }

    public void event(String type, String message, String detail) {
        event(type, activeAgent, message, detail, null);
    }

    public void event(String type, String agent, String message, String detail, Long durationMs) {
        events.add(new AgentEvent(Instant.now(), type, agent, message, truncate(detail, 4000), durationMs));
    }

    public void toolCall(String origin, String name, String arguments, String result, long durationMs, boolean error) {
        toolCalls.add(new ToolCallRecord(Instant.now(), activeAgent, origin, name,
                truncate(arguments, 1500), truncate(result, 3000), durationMs, error));
    }

    public List<AgentEvent> events() {
        return List.copyOf(events);
    }

    public List<ToolCallRecord> toolCalls() {
        return List.copyOf(toolCalls);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "... [truncated]";
    }
}
