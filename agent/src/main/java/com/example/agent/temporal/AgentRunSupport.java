package com.example.agent.temporal;

import java.util.List;
import java.util.Objects;

import com.example.agent.agent.AgentPlan;
import com.example.agent.agent.AgentSupport;
import com.example.agent.agent.PlanStep;
import com.example.agent.core.PromptSafety;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Goal construction, plan normalization and memory writes, shared by the synchronous
 * {@code AgentOrchestrator} and the durable Temporal run.
 */
@Service
public class AgentRunSupport {

    private final ChatMemory chatMemory;
    private final int maxPlanSteps;

    public AgentRunSupport(ChatMemory chatMemory,
            @Value("${agent.agents.max-plan-steps:4}") int maxPlanSteps) {
        this.chatMemory = chatMemory;
        this.maxPlanSteps = Math.max(1, maxPlanSteps);
    }

    public String buildGoal(String conversationId, String task) {
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
        return PromptSafety.data(goal.toString().trim())
                + "\n\nCurrent task: " + task;
    }

    public AgentPlan normalizePlan(AgentPlan plan) {
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

    public void remember(String conversationId, String task, String answer) {
        chatMemory.add(conversationId,
                List.of(new UserMessage(task), new AssistantMessage(answer)));
    }
}
