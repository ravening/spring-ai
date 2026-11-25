package com.example.agent.temporal;

import java.util.List;

import com.example.agent.agent.StepResult;

public record WriteRequest(
        String goal,
        List<StepResult> priorResults) {
}
