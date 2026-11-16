package com.example.agent.agent;

import java.util.List;

import com.example.agent.core.AgentTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorApprovalTests {

    @Mock
    private PlannerAgent planner;

    @Mock
    private ResearchAgent researcher;

    @Mock
    private AnalystAgent analyst;

    @Mock
    private WriterAgent writer;

    @Mock
    private CriticAgent critic;

    @Mock
    private ChatMemory chatMemory;

    @Test
    void runUsesApprovedPlanWithoutReplanning() {
        AgentPlan approved = new AgentPlan("Approved plan",
                List.of(new PlanStep("RESEARCHER", "Gather the facts")));
        when(chatMemory.get("chat-1")).thenReturn(List.of());
        when(researcher.run(anyString(), anyString(), anyList(), any(AgentTrace.class))).thenReturn("facts");
        when(writer.synthesize(anyString(), anyList(), any(AgentTrace.class))).thenReturn("draft");
        when(critic.review(anyString(), anyString(), anyList(), any(AgentTrace.class)))
                .thenReturn(new Review(true, "Ready", List.of()));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                planner, researcher, analyst, writer, critic, chatMemory, 4, false);

        AgentRunResponse response = orchestrator.run(new AgentRequest("do research", "chat-1", approved));

        verifyNoInteractions(planner);
        assertThat(response.plan()).isEqualTo(approved);
        assertThat(response.answer()).isEqualTo("draft");
        assertThat(response.events()).extracting("type").contains("plan_approved");
    }

    @Test
    void persistentlyRejectingCriticCannotLoopForever() {
        when(chatMemory.get("chat-1")).thenReturn(List.of());
        when(planner.plan(anyString(), any(AgentTrace.class))).thenReturn(
                new AgentPlan("Plan", List.of(new PlanStep("RESEARCHER", "Find facts"))));
        when(researcher.run(anyString(), anyString(), anyList(), any(AgentTrace.class))).thenReturn("facts");
        when(writer.synthesize(anyString(), anyList(), any(AgentTrace.class))).thenReturn("draft");
        when(writer.revise(anyString(), anyString(), anyString(), any(AgentTrace.class))).thenReturn("revised draft");
        when(critic.review(anyString(), anyString(), anyList(), any(AgentTrace.class)))
                .thenReturn(new Review(false, "Needs work", List.of("Add a concrete number")));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                planner, researcher, analyst, writer, critic, chatMemory, 4, false);

        AgentRunResponse response = orchestrator.run(new AgentRequest("task", "chat-1", null));

        verify(writer, times(2)).revise(anyString(), anyString(), anyString(), any(AgentTrace.class));
        verify(critic, times(3)).review(anyString(), anyString(), anyList(), any(AgentTrace.class));
        assertThat(response.answer()).isEqualTo("revised draft");
        assertThat(response.events()).extracting("type").contains("review_unresolved");
    }

    @Test
    void planDoesNotExecuteAnyStep() {
        when(chatMemory.get("chat-1")).thenReturn(List.of());
        when(planner.plan(anyString(), any(AgentTrace.class))).thenReturn(
                new AgentPlan("Fresh plan", List.of(new PlanStep("RESEARCHER", "Find facts"))));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                planner, researcher, analyst, writer, critic, chatMemory, 4, false);

        AgentPlan plan = orchestrator.plan(new AgentRequest("task", "chat-1", null));

        assertThat(plan.summary()).isEqualTo("Fresh plan");
        verifyNoInteractions(researcher, analyst, writer, critic);
    }
}
