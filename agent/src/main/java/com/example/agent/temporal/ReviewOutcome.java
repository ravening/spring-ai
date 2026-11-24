package com.example.agent.temporal;

import java.util.List;

import com.example.agent.agent.Review;
import com.example.agent.core.AgentEvent;

public record ReviewOutcome(
        Review review,
        List<AgentEvent> events) {
}
