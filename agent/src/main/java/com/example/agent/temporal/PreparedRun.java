package com.example.agent.temporal;

import com.example.agent.agent.AgentPlan;

public record PreparedRun(
        String goal,
        AgentPlan plan) {
}
