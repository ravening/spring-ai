package com.example.agent.temporal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.example.agent.agent.AgentPlan;
import com.example.agent.agent.PlanStep;
import com.example.agent.agent.StepResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.spring.boot.WorkflowImpl;

/**
 * One Temporal workflow per durable agent run. The class is instantiated by Temporal,
 * not by Spring: no {@code @Component}, no injected beans, and no I/O, clocks,
 * randomness or sleeping here — only {@link Workflow} helpers, so replay after a
 * crash is deterministic. Completed activities are answered from history and are
 * never re-executed.
 */
@WorkflowImpl(workers = "agent-run")
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private static final int MAX_REVISIONS = 2;
    private static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);

    private final AgentRunActivities activities = Workflow.newActivityStub(
            AgentRunActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setMaximumAttempts(3)
                            .setDoNotRetry(IllegalArgumentException.class.getName())
                            .build())
                    .build());

    private final List<StepState> steps = new ArrayList<>();
    private RunStatus status = RunStatus.PENDING;
    private Boolean approval;
    private String answer;
    private String error;

    @Override
    public String run(AgentRunInput input) {
        try {
            status = RunStatus.RUNNING;

            PreparedRun prepared = activities.prepare(input);
            buildStepStates(prepared.plan());

            if (input.requireApproval() && input.approvedPlan() == null) {
                status = RunStatus.WAITING_APPROVAL;
                boolean answered = Workflow.await(APPROVAL_TIMEOUT, () -> approval != null);
                if (!answered || !Boolean.TRUE.equals(approval)) {
                    status = RunStatus.CANCELLED;
                    return null;
                }
                status = RunStatus.RUNNING;
            }

            List<StepResult> prior = new ArrayList<>();
            List<PlanStep> planSteps = prepared.plan().steps();

            for (int i = 0; i < planSteps.size(); i++) {
                PlanStep step = planSteps.get(i);
                int attempt = steps.get(i).attempt() + 1;

                steps.get(i).markRunning(Workflow.currentTimeMillis(), attempt);

                StepOutcome outcome;
                try {
                    outcome = activities.executeStep(new StepExecution(
                            input.runId(),
                            input.conversationId(),
                            i,
                            step,
                            List.copyOf(prior),
                            prepared.goal()));
                }
                catch (Exception ex) {
                    steps.get(i).markFailed(rootMessage(ex));
                    throw ex;
                }

                steps.get(i).markCompleted(Workflow.currentTimeMillis());
                prior.add(new StepResult(step.agent(), step.instruction(), outcome.output()));
            }

            DraftOutcome draft = activities.write(
                    new WriteRequest(prepared.goal(), List.copyOf(prior)));

            ReviewOutcome review = activities.review(
                    new ReviewRequest(prepared.goal(), draft.draft(), List.copyOf(prior)));

            int revisions = 0;
            while (!review.review().approved() && revisions < MAX_REVISIONS) {
                draft = activities.revise(new ReviseRequest(
                        prepared.goal(),
                        draft.draft(),
                        review.review().feedback(),
                        List.copyOf(prior)));

                review = activities.review(new ReviewRequest(
                        prepared.goal(), draft.draft(), List.copyOf(prior)));

                revisions++;
            }

            activities.remember(new RememberRequest(
                    input.conversationId(), input.task(), draft.draft(), input.runId()));

            answer = draft.draft();
            status = RunStatus.COMPLETED;
            return answer;
        }
        catch (Exception ex) {
            error = rootMessage(ex);
            status = RunStatus.FAILED;
            throw ex;
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null ? message : ex.getMessage();
    }

    @Override
    public AgentRunView view() {
        return new AgentRunView(
                Workflow.getInfo().getWorkflowId(),
                status,
                currentStep(),
                lastCompletedStep(),
                steps.stream().map(StepState::view).toList(),
                answer,
                error);
    }

    @Override
    public void approvePlan(boolean approved) {
        this.approval = approved;
    }

    private void buildStepStates(AgentPlan plan) {
        steps.clear();
        List<PlanStep> planSteps = plan.steps();
        for (int i = 0; i < planSteps.size(); i++) {
            steps.add(new StepState(i, planSteps.get(i).agent(), planSteps.get(i).instruction()));
        }
    }

    private int currentStep() {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).status() == StepStatus.RUNNING) {
                return i + 1;
            }
        }
        return steps.size();
    }

    private Integer lastCompletedStep() {
        Integer last = null;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).status() == StepStatus.COMPLETED) {
                last = i + 1;
            }
        }
        return last;
    }
}
