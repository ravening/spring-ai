package com.example.agent.temporal;

import java.util.List;

import com.example.agent.core.AgentEvent;

public record DraftOutcome(
        String draft,
        List<AgentEvent> events) {
}
