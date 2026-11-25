package com.example.agent.temporal;

/**
 * Workflow-local state of one plan step. Rebuilt deterministically during replay;
 * completed activities are answered from history, so the state matches the history.
 */
final class StepState {

    private final int index;
    private final String agent;
    private final String instruction;
    private StepStatus status = StepStatus.PENDING;
    private int attempt;
    private long startedAt;
    private Long finishedAt;
    private String error;

    StepState(int index, String agent, String instruction) {
        this.index = index;
        this.agent = agent;
        this.instruction = instruction;
    }

    int attempt() {
        return attempt;
    }

    StepStatus status() {
        return status;
    }

    void markRunning(long now, int attempt) {
        this.status = StepStatus.RUNNING;
        this.attempt = attempt;
        this.startedAt = now;
        this.error = null;
    }

    void markCompleted(long now) {
        this.status = StepStatus.COMPLETED;
        this.finishedAt = now;
    }

    void markFailed(String message) {
        this.status = StepStatus.FAILED;
        this.error = message;
    }

    StepView view() {
        return new StepView(
                index, agent, instruction, status, attempt, startedAt, finishedAt, error);
    }
}
