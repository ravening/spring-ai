package com.example.agent.temporal;

import com.example.agent.agent.AgentPlan;

/** Input of one durable agent run. The workflow ID equals {@code runId}. */
public record AgentRunInput(
        String runId,
        String conversationId,
        String userId,
        String task,
        AgentPlan approvedPlan,
        boolean requireApproval) {
}
