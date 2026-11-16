package com.example.agent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.example.agent.core.AgentTrace;
import com.example.agent.core.ConversationId;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentOrchestrator {

    private final PlannerAgent planner;
    private final ResearchAgent researcher;
    private final AnalystAgent analyst;
    private final WriterAgent writer;
    private final CriticAgent critic;
    private final ChatMemory chatMemory;
    private final int maxPlanSteps;

    public AgentOrchestrator(
            PlannerAgent planner,
            ResearchAgent researcher,
            AnalystAgent analyst,
            WriterAgent writer,
            CriticAgent critic,
            ChatMemory chatMemory,
            @Value("${agent.agents.max-plan-steps:4}") int maxPlanSteps) {
        this.planner = planner;
        this.researcher = researcher;
        this.analyst = analyst;
        this.writer = writer;
        this.critic = critic;
        this.chatMemory = chatMemory;
        this.maxPlanSteps = Math.max(1, maxPlanSteps);
    }

    public AgentRunResponse run(AgentRequest request) {
        if (request == null || !StringUtils.hasText(request.task())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task is required");
        }

        String conversationId = ConversationId.resolve(request.conversationId());
        String task = request.task().trim();
        AgentTrace trace = new AgentTrace(conversationId);
        trace.event("run_started", "orchestrator", "Task: " + AgentSupport.truncate(task, 500), null, null);

        String goal = buildGoal(conversationId, task);
        AgentPlan plan = normalizePlan(planner.plan(goal, trace));

        List<StepResult> results = new ArrayList<>();
        int stepNumber = 0;
        for (PlanStep step : plan.steps()) {
            stepNumber++;
            long start = System.nanoTime();
            trace.event("delegation", "orchestrator",
                    "Step %d -> %s".formatted(stepNumber, step.agent()), step.instruction(), null);

            String output = switch (step.agent()) {
                case "ANALYST" -> analyst.run(goal, step.instruction(), results, trace);
                case "WRITER" -> writer.run(goal, step.instruction(), results, trace);
                default -> researcher.run(goal, step.instruction(), results, trace);
            };

            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            results.add(new StepResult(step.agent(), step.instruction(), output));
            trace.event("agent_result", step.agent(), AgentSupport.summarize(output, 200),
                    AgentSupport.truncate(output, 4000), durationMs);
        }

        String draft = writer.synthesize(goal, results, trace);
        trace.event("draft_ready", "writer", "Writer produced a draft", null, null);

        String review = critic.review(goal, draft, results, trace);
        boolean approved = review != null
                && review.strip().toUpperCase(Locale.ROOT).startsWith("APPROVED");
        trace.event("review", "critic", approved ? "Draft approved" : "Issues found", review, null);

        String finalAnswer;
        if (approved) {
            finalAnswer = draft;
        }
        else {
            finalAnswer = writer.revise(goal, draft, review, trace);
            trace.event("revision", "writer", "Writer revised the answer after review", null, null);
        }

        remember(conversationId, task, finalAnswer);
        trace.event("run_completed", "orchestrator", "Multi-agent run completed", null, null);
        return new AgentRunResponse(conversationId, finalAnswer, plan, trace.events(), trace.toolCalls());
    }

    private AgentPlan normalizePlan(AgentPlan plan) {
        if (plan == null) {
            plan = new AgentPlan("Plan created from the task.", List.of());
        }
        List<PlanStep> steps = plan.steps().stream()
                .filter(Objects::nonNull)
                .filter(step -> StringUtils.hasText(step.instruction()))
                .limit(maxPlanSteps)
                .map(step -> new PlanStep(
                        AgentSupport.normalizeAgent(step.agent()),
                        step.instruction().trim()))
                .toList();

        if (steps.isEmpty()) {
            steps = List.of(new PlanStep("RESEARCHER",
                    "Gather the facts and tool data needed to answer the task."));
        }

        String summary = StringUtils.hasText(plan.summary())
                ? plan.summary().trim()
                : "Planner produced a task plan.";
        return new AgentPlan(summary, steps);
    }

    private String buildGoal(String conversationId, String task) {
        List<Message> messages = chatMemory.get(conversationId).stream()
                .filter(message -> message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT)
                .filter(message -> StringUtils.hasText(message.getText()))
                .toList();
        if (messages.isEmpty()) {
            return task;
        }

        List<Message> recent = messages.subList(Math.max(0, messages.size() - 6), messages.size());
        StringBuilder goal = new StringBuilder("Recent conversation:\n");
        for (Message message : recent) {
            goal.append(message.getMessageType() == MessageType.USER ? "User: " : "Assistant: ")
                    .append(AgentSupport.truncate(message.getText().replaceAll("\\s+", " ").strip(), 600))
                    .append('\n');
        }
        return goal.append("\nCurrent task: ").append(task).toString();
    }

    private void remember(String conversationId, String task, String answer) {
        chatMemory.add(conversationId,
                List.of(new UserMessage(task), new AssistantMessage(answer)));
    }
}
