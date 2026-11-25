package com.example.agent.temporal;

import java.util.List;

import com.example.agent.core.AgentEvent;
import com.example.agent.core.ToolCallRecord;

public record StepOutcome(
        String output,
        List<AgentEvent> events,
        List<ToolCallRecord> toolCalls,
        long durationMs,
        int attempt) {
}
