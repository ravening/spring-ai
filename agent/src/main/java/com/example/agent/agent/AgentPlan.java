package com.example.agent.agent;

import java.util.List;

public record AgentPlan(String summary, List<PlanStep> steps) {

    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
