package com.example.agent.temporal;

import java.util.List;

import com.example.agent.agent.StepResult;

public record ReviseRequest(
        String goal,
        String draft,
        String feedback,
        List<StepResult> priorResults) {
}
