package com.example.agent.temporal;

import java.util.List;

/** Query result of a durable run: status, current/last completed step and per-step state. */
public record AgentRunView(
        String runId,
        RunStatus status,
        int currentStep,
        Integer lastCompletedStep,
        List<StepView> steps,
        String answer,
        String error) {
}
