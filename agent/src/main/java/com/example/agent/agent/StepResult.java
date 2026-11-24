package com.example.agent.agent;

/** Output of one plan step. Public because it travels inside Temporal activity payloads. */
public record StepResult(String agent, String instruction, String output) {
}
