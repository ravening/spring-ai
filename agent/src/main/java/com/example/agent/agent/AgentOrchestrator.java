package com.example.agent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.agent.core.AgentTrace;
import com.example.agent.core.ConversationId;
import com.example.agent.temporal.AgentRunSupport;
import com.example.agent.temporal.AgentStepExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentOrchestrator {

    private static final int MAX_PARALLEL_STEPS = 4;
    private static final long STEP_TIMEOUT_SECONDS = 180;
    private static final int MAX_REVISIONS = 2;

    private final PlannerAgent planner;
    private final AgentStepExecutor stepExecutor;
    private final WriterAgent writer;
    private final CriticAgent critic;
    private final AgentRunSupport support;
    private final boolean parallel;

    public AgentOrchestrator(
            PlannerAgent planner,
            AgentStepExecutor stepExecutor,
            WriterAgent writer,
            CriticAgent critic,
            AgentRunSupport support,
            @Value("${agent.agents.parallel:false}") boolean parallel) {
        this.planner = planner;
        this.stepExecutor = stepExecutor;
        this.writer = writer;
        this.critic = critic;
        this.support = support;
        this.parallel = parallel;
    }

    /** Plans a task without executing any agent step, for human review in the UI. */
    public AgentPlan plan(AgentRequest request) {
        if (request == null || !StringUtils.hasText(request.task())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task is required");
        }
        String conversationId = ConversationId.resolve(request.conversationId());
        String task = request.task().trim();
        AgentTrace trace = new AgentTrace(conversationId);
        trace.event("plan_requested", "orchestrator", "Task: " + AgentSupport.truncate(task, 500), null, null);
        return support.normalizePlan(planner.plan(support.buildGoal(conversationId, task), trace));
    }

    public AgentRunResponse run(AgentRequest request) {
        if (request == null || !StringUtils.hasText(request.task())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task is required");
        }

        String conversationId = ConversationId.resolve(request.conversationId());
        String task = request.task().trim();
        AgentTrace trace = new AgentTrace(conversationId);
        trace.event("run_started", "orchestrator", "Task: " + AgentSupport.truncate(task, 500), null, null);

        String goal = support.buildGoal(conversationId, task);
        boolean approvedPlan = request.approvedPlan() != null;
        AgentPlan plan = approvedPlan
                ? support.normalizePlan(request.approvedPlan())
                : support.normalizePlan(planner.plan(goal, trace));
        if (approvedPlan) {
            trace.event("plan_approved", "orchestrator", "Using the plan approved by the user", plan.summary(), null);
        }

        List<StepResult> results = executeSteps(plan.steps(), goal, trace);

        String draft = writer.synthesize(goal, results, trace);
        trace.event("draft_ready", "writer", "Writer produced a draft", null, null);

        String finalAnswer = reviewAndRevise(goal, draft, results, trace);

        support.remember(conversationId, task, finalAnswer);
        trace.event("run_completed", "orchestrator", "Multi-agent run completed", null, null);
        return new AgentRunResponse(conversationId, finalAnswer, plan, trace.events(), trace.toolCalls());
    }

    /** Reviews the draft and asks the writer to revise at most MAX_REVISIONS times. */
    private String reviewAndRevise(String goal, String draft, List<StepResult> results, AgentTrace trace) {
        Review review = critic.review(goal, draft, results, trace);
        int revisions = 0;
        while (!review.approved() && revisions < MAX_REVISIONS) {
            trace.event("review", "critic", "Issues found", review.feedback(), null);
            revisions++;
            draft = writer.revise(goal, draft, review.feedback(), trace);
            trace.event("revision", "writer", "Writer revised the answer (" + revisions + ")", null, null);
            review = critic.review(goal, draft, results, trace);
        }
        if (review.approved()) {
            trace.event("review", "critic", "Draft approved", review.summary(), null);
        }
        else {
            trace.event("review_unresolved", "critic",
                    "Max revisions reached; returning the best draft", review.feedback(), null);
        }
        return draft;
    }

    private List<StepResult> executeSteps(List<PlanStep> steps, String goal, AgentTrace trace) {
        if (parallel && steps.size() > 1) {
            return executeParallel(steps, goal, trace);
        }
        return executeSequential(steps, goal, trace);
    }

    private List<StepResult> executeSequential(List<PlanStep> steps, String goal, AgentTrace trace) {
        List<StepResult> results = new ArrayList<>();
        int stepNumber = 0;
        for (PlanStep step : steps) {
            stepNumber++;
            long start = System.nanoTime();
            trace.event("delegation", "orchestrator",
                    "Step %d -> %s".formatted(stepNumber, step.agent()), step.instruction(), null);

            String output = stepExecutor.execute(step, results, trace, goal);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            results.add(new StepResult(step.agent(), step.instruction(), output));
            recordStepResult(trace, step, output, durationMs);
        }
        return results;
    }

    /**
     * Runs plan steps concurrently with a bounded executor and returns results in plan
     * order. Steps do not see each other's output in this mode, so it is best suited to
     * plans whose steps are independent data-gathering tasks.
     */
    private List<StepResult> executeParallel(List<PlanStep> steps, String goal, AgentTrace trace) {
        List<StepResult> noPriorResults = List.of();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(steps.size(), MAX_PARALLEL_STEPS));
        try {
            List<Future<StepResult>> futures = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                PlanStep step = steps.get(i);
                int stepNumber = i + 1;
                trace.event("delegation", "orchestrator",
                        "Step %d -> %s (parallel)".formatted(stepNumber, step.agent()), step.instruction(), null);
                futures.add(pool.submit(() -> {
                    long start = System.nanoTime();
                    String output = stepExecutor.execute(step, noPriorResults, trace, goal);
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    recordStepResult(trace, step, output, durationMs);
                    return new StepResult(step.agent(), step.instruction(), output);
                }));
            }

            List<StepResult> results = new ArrayList<>();
            for (Future<StepResult> future : futures) {
                try {
                    results.add(future.get(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                }
                catch (Exception ex) {
                    trace.event("step_failed", "orchestrator",
                            "A parallel step failed: " + ex.getMessage(), null, null);
                    results.add(new StepResult("SYSTEM", "Failed step", "Step failed: " + ex.getMessage()));
                }
            }
            return results;
        }
        finally {
            pool.shutdownNow();
        }
    }

    private static void recordStepResult(AgentTrace trace, PlanStep step, String output, long durationMs) {
        trace.event("agent_result", step.agent(), AgentSupport.summarize(output, 200),
                AgentSupport.truncate(output, 4000), durationMs);
    }
}
