package com.example.agent.temporal;

import java.util.List;

import com.example.agent.agent.PlanStep;
import com.example.agent.agent.StepResult;

public record StepExecution(
        String runId,
        String conversationId,
        int index,
        PlanStep step,
        List<StepResult> priorResults,
        String goal) {
}
