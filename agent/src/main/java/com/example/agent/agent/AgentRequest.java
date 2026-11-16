package com.example.agent.agent;

public record AgentRequest(String task, String conversationId, AgentPlan approvedPlan) {
}
