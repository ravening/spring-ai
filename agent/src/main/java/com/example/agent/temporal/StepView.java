package com.example.agent.temporal;

public record StepView(
        int index,
        String agent,
        String instruction,
        StepStatus status,
        int attempt,
        long startedAt,
        Long finishedAt,
        String error) {
}
