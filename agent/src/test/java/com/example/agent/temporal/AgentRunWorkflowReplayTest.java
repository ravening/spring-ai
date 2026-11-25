package com.example.agent.temporal;

import java.util.List;

import com.example.agent.agent.AgentPlan;
import com.example.agent.agent.PlanStep;
import com.example.agent.agent.Review;

import io.temporal.client.WorkflowClient;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs a workflow once, then replays its recorded history through
 * {@link WorkflowReplayer}. This fails on any non-determinism in
 * {@link AgentRunWorkflowImpl} (I/O, clocks, randomness, changed activity order).
 */
class AgentRunWorkflowReplayTest {

    @Test
    void workflowReplaysFromRecordedHistory() throws Exception {
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
        try {
            Worker worker = testEnv.newWorker("agent-run");
            worker.registerWorkflowImplementationTypes(AgentRunWorkflowImpl.class);
            AgentRunActivities activities = mock(AgentRunActivities.class);
            List<PlanStep> steps = List.of(
                    new PlanStep("RESEARCHER", "Find the facts"),
                    new PlanStep("ANALYST", "Compute the total"));
            when(activities.prepare(any()))
                    .thenReturn(new PreparedRun("goal", new AgentPlan("plan", steps)));
            when(activities.executeStep(any())).thenAnswer(invocation -> {
                StepExecution execution = invocation.getArgument(0);
                return new StepOutcome("out-" + execution.index(), List.of(), List.of(), 5, 1);
            });
            when(activities.write(any())).thenReturn(new DraftOutcome("draft", List.of()));
            when(activities.review(any()))
                    .thenReturn(new ReviewOutcome(new Review(true, "ok", List.of()), List.of()));
            worker.registerActivitiesImplementations(activities);
            testEnv.start();

            WorkflowClient client = testEnv.getWorkflowClient();
            AgentRunWorkflow workflow = client.newWorkflowStub(
                    AgentRunWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue("agent-run")
                            .setWorkflowId("run-replay")
                            .build());
            WorkflowClient.start(workflow::run,
                    new AgentRunInput("run-replay", "conv-1", "local", "task", null, false));

            assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("draft");

            WorkflowExecutionHistory history = client.fetchHistory("run-replay");
            WorkflowReplayer.replayWorkflowExecution(history, AgentRunWorkflowImpl.class);
        }
        finally {
            testEnv.close();
        }
    }
}
