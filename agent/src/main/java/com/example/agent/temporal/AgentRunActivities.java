package com.example.agent.temporal;

import io.temporal.activity.ActivityInterface;

/**
 * One activity per phase of a durable agent run. Activities are the only place where
 * I/O happens; completed activities are replayed from history, never re-executed.
 */
@ActivityInterface
public interface AgentRunActivities {

    PreparedRun prepare(AgentRunInput input);

    StepOutcome executeStep(StepExecution execution);

    DraftOutcome write(WriteRequest request);

    ReviewOutcome review(ReviewRequest request);

    DraftOutcome revise(ReviseRequest request);

    void remember(RememberRequest request);
}
