package com.example.agent.agent;

import java.util.List;

import com.example.agent.core.AgentEvent;
import com.example.agent.core.ToolCallRecord;

public record AgentRunResponse(
        String conversationId,
        String answer,
        AgentPlan plan,
        List<AgentEvent> events,
        List<ToolCallRecord> toolCalls) {
}
