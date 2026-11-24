package com.example.agent.temporal;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.example.agent.agent.AgentPlan;
import com.example.agent.agent.CriticAgent;
import com.example.agent.agent.PlannerAgent;
import com.example.agent.agent.Review;
import com.example.agent.agent.WriterAgent;
import com.example.agent.core.AgentTrace;

import io.temporal.activity.Activity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring bean backing {@link AgentRunActivities}. All model calls, tool use and memory
 * access happen here, inside activity boundaries, so a crash retries only the current
 * activity and earlier steps stay completed in the Temporal history.
 */
@Component("agentRunActivities")
public class AgentRunActivitiesImpl implements AgentRunActivities {

    private final PlannerAgent planner;
    private final AgentStepExecutor stepExecutor;
    private final WriterAgent writer;
    private final CriticAgent critic;
    private final AgentRunSupport support;
    private final AgentRunMemoryStore memoryStore;
    private final Duration stepDelay;

    public AgentRunActivitiesImpl(PlannerAgent planner,
            AgentStepExecutor stepExecutor,
            WriterAgent writer,
            CriticAgent critic,
            AgentRunSupport support,
            AgentRunMemoryStore memoryStore,
            @Value("${agent.temporal.demo.step-delay:0s}") Duration stepDelay) {
        this.planner = planner;
        this.stepExecutor = stepExecutor;
        this.writer = writer;
        this.critic = critic;
        this.support = support;
        this.memoryStore = memoryStore;
        this.stepDelay = stepDelay == null ? Duration.ZERO : stepDelay;
    }

    @Override
    public PreparedRun prepare(AgentRunInput input) {
        if (input == null || input.task() == null || input.task().isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        AgentTrace trace = new AgentTrace(input.conversationId());
        String goal = support.buildGoal(input.conversationId(), input.task());

        AgentPlan plan = input.approvedPlan() != null
                ? support.normalizePlan(input.approvedPlan())
                : support.normalizePlan(planner.plan(goal, trace));

        return new PreparedRun(goal, plan);
    }

    @Override
    public StepOutcome executeStep(StepExecution execution) {
        demoDelay();
        AgentTrace trace = new AgentTrace(execution.conversationId());
        long start = System.nanoTime();
        String output = stepExecutor.execute(
                execution.step(),
                execution.priorResults(),
                trace,
                execution.goal());
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new StepOutcome(output, trace.events(), trace.toolCalls(), durationMs,
                Activity.getExecutionContext().getInfo().getAttempt());
    }

    @Override
    public DraftOutcome write(WriteRequest request) {
        AgentTrace trace = new AgentTrace("");
        String draft = writer.synthesize(request.goal(), request.priorResults(), trace);
        return new DraftOutcome(draft, trace.events());
    }

    @Override
    public ReviewOutcome review(ReviewRequest request) {
        AgentTrace trace = new AgentTrace("");
        Review review = critic.review(request.goal(), request.draft(), request.priorResults(), trace);
        return new ReviewOutcome(review, trace.events());
    }

    @Override
    public DraftOutcome revise(ReviseRequest request) {
        AgentTrace trace = new AgentTrace("");
        String draft = writer.revise(request.goal(), request.draft(), request.feedback(), trace);
        return new DraftOutcome(draft, trace.events());
    }

    @Override
    public void remember(RememberRequest request) {
        // Idempotent by runId: if a retry reaches this activity after the messages were
        // already written, the claim fails and nothing is appended twice.
        if (memoryStore.claim(request.runId())) {
            support.remember(request.conversationId(), request.task(), request.answer());
        }
    }

    /**
     * Artificial per-step delay used only by the crash demo
     * (scripts/temporal-crash-demo.sh) to widen the window in which a kill -9 lands
     * while a step is running. Heartbeats keep the activity alive until the worker
     * dies, so the heartbeat timeout is what detects the crash.
     */
    private void demoDelay() {
        long remainingMs = stepDelay.toMillis();
        while (remainingMs > 0) {
            long chunk = Math.min(500, remainingMs);
            try {
                Thread.sleep(chunk);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            remainingMs -= chunk;
            Activity.getExecutionContext().heartbeat("demo step delay");
        }
    }
}
