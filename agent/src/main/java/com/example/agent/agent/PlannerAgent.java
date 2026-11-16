package com.example.agent.agent;

import java.util.List;

import com.example.agent.core.AgentTrace;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class PlannerAgent {

    private final ChatClient chatClient;

    public PlannerAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem("""
                        You are the planning agent in a multi-agent workflow.

                        Break the user's task into 2 to 4 short, concrete steps. Every step must
                        name exactly one of these agents:
                        - RESEARCHER: gathers facts with tools (weather, inventory, FX, shipping,
                          support tickets). Use it whenever external or private data is needed.
                        - ANALYST: performs calculations and quantitative comparisons with the
                          calculator. Use it for any arithmetic.
                        - WRITER: drafts one focused section of the final answer.

                        Rules:
                        - Start with RESEARCHER when facts or tool data are needed.
                        - Use ANALYST only for actual arithmetic, totals, conversions or
                          quantitative comparisons. Omit it when no calculation is required.
                        - Include only steps that are necessary for this task.
                        - Do not add critique, review or final-synthesis steps; those happen later.
                        - Each instruction must be self-contained and specific.
                        - Keep the plan short. Return only the structured plan.
                        """)
                .build();
    }

    public AgentPlan plan(String goal, AgentTrace trace) {
        trace.activeAgent("planner");
        try {
            AgentPlan plan = chatClient.prompt()
                    .user(goal)
                    .call()
                    .entity(AgentPlan.class);

            if (plan == null || plan.steps().isEmpty()) {
                throw new IllegalStateException("The planner returned an empty plan");
            }
            trace.event("plan_created", "planner", plan.summary(), null, null);
            return plan;
        }
        catch (Exception ex) {
            AgentPlan fallback = new AgentPlan(
                    "Fallback plan: gather the facts needed, then write the answer.",
                    List.of(new PlanStep("RESEARCHER", "Gather the facts and tool data needed to answer the task.")));
            trace.event("plan_fallback", "planner", "Using the fallback plan", ex.getMessage(), null);
            return fallback;
        }
    }
}
