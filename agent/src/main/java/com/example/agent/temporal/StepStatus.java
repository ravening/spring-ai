package com.example.agent.temporal;

public enum StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    NEEDS_RECONCILIATION
}
