package com.example.agent.temporal;

import java.util.List;

import com.example.agent.agent.StepResult;

public record ReviewRequest(
        String goal,
        String draft,
        List<StepResult> priorResults) {
}
