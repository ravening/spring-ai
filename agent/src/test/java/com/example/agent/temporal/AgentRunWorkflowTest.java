package com.example.agent.temporal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.example.agent.agent.AgentPlan;
import com.example.agent.agent.PlanStep;
import com.example.agent.agent.Review;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workflow tests against an in-process Temporal test server with a mocked activity
 * implementation. Real-time mode (no time skipping) is used so the approval wait is
 * observable through the query method.
 */
class AgentRunWorkflowTest {

    private static final String TASK_QUEUE = "agent-run";

    private TestWorkflowEnvironment testEnv;
    private AgentRunActivities activities;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(AgentRunWorkflowImpl.class);
        activities = mock(AgentRunActivities.class);
        worker.registerActivitiesImplementations(activities);
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void happyPathExecutesEachStepOnceAndCompletes() {
        stubHappyPath(3);

        AgentRunWorkflow workflow = start(input("run-happy", false));
        String result = WorkflowStub.fromTyped(workflow).getResult(String.class);

        assertThat(result).isEqualTo("draft");
        verify(activities, times(3)).executeStep(any());
        verify(activities, times(1)).remember(any());
        AgentRunView view = workflow.view();
        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(view.answer()).isEqualTo("draft");
        assertThat(view.currentStep()).isEqualTo(3);
        assertThat(view.lastCompletedStep()).isEqualTo(3);
        assertThat(view.steps()).hasSize(3)
                .allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.COMPLETED));
    }

    @Test
    void failingStepIsRetriedAndEarlierStepsAreNotReExecuted() {
        stubHappyPath(3);
        AtomicInteger stepTwoAttempts = new AtomicInteger();
        when(activities.executeStep(any())).thenAnswer(invocation -> {
            StepExecution execution = invocation.getArgument(0);
            if (execution.index() == 1 && stepTwoAttempts.incrementAndGet() <= 2) {
                throw new RuntimeException("transient provider error");
            }
            return new StepOutcome("out-" + execution.index(), List.of(), List.of(), 5, 1);
        });

        AgentRunWorkflow workflow = start(input("run-retry", false));
        String result = WorkflowStub.fromTyped(workflow).getResult(String.class);

        assertThat(result).isEqualTo("draft");
        verify(activities, times(1)).executeStep(argThat((StepExecution e) -> e.index() == 0));
        verify(activities, times(3)).executeStep(argThat((StepExecution e) -> e.index() == 1));
        verify(activities, times(1)).executeStep(argThat((StepExecution e) -> e.index() == 2));
        assertThat(workflow.view().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void nonRetryableFailureFailsWorkflowWithoutRunningLaterSteps() {
        stubHappyPath(3);
        when(activities.executeStep(any())).thenAnswer(invocation -> {
            StepExecution execution = invocation.getArgument(0);
            if (execution.index() == 0) {
                throw new IllegalArgumentException("task is invalid");
            }
            return new StepOutcome("out-" + execution.index(), List.of(), List.of(), 5, 1);
        });

        AgentRunWorkflow workflow = start(input("run-fail", false));

        assertThatThrownBy(() -> WorkflowStub.fromTyped(workflow).getResult(String.class))
                .isInstanceOf(WorkflowFailedException.class);
        verify(activities, times(1)).executeStep(any());
        verify(activities, never()).write(any());
        AgentRunView view = workflow.view();
        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.steps().get(0).status()).isEqualTo(StepStatus.FAILED);
        assertThat(view.steps().get(0).error()).contains("task is invalid");
    }

    @Test
    void approvalSignalResumesWaitingRun() throws Exception {
        stubHappyPath(2);

        AgentRunWorkflow workflow = start(input("run-approve", true));
        AgentRunView waiting = awaitView(workflow, v -> v.status() == RunStatus.WAITING_APPROVAL);

        assertThat(waiting.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(waiting.steps()).hasSize(2)
                .allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.PENDING));
        assertThat(waiting.lastCompletedStep()).isNull();
        verify(activities, never()).executeStep(any());

        workflow.approvePlan(true);
        String result = WorkflowStub.fromTyped(workflow).getResult(String.class);

        assertThat(result).isEqualTo("draft");
        verify(activities, times(2)).executeStep(any());
        assertThat(workflow.view().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void rejectedApprovalCancelsRunWithoutExecutingSteps() throws Exception {
        stubHappyPath(2);

        AgentRunWorkflow workflow = start(input("run-reject", true));
        awaitView(workflow, v -> v.status() == RunStatus.WAITING_APPROVAL);

        workflow.approvePlan(false);
        String result = WorkflowStub.fromTyped(workflow).getResult(String.class);

        assertThat(result).isNull();
        verify(activities, never()).executeStep(any());
        assertThat(workflow.view().status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void duplicateApprovalSignalIsHarmless() throws Exception {
        stubHappyPath(2);
        CountDownLatch stepGate = new CountDownLatch(1);
        when(activities.executeStep(any())).thenAnswer(invocation -> {
            stepGate.await(30, TimeUnit.SECONDS);
            StepExecution execution = invocation.getArgument(0);
            return new StepOutcome("out-" + execution.index(), List.of(), List.of(), 5, 1);
        });

        AgentRunWorkflow workflow = start(input("run-dup", true));
        awaitView(workflow, v -> v.status() == RunStatus.WAITING_APPROVAL);

        workflow.approvePlan(true);
        awaitView(workflow, v -> v.status() == RunStatus.RUNNING);
        workflow.approvePlan(true);
        stepGate.countDown();

        assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("draft");
        assertThat(workflow.view().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void queryDuringStepReportsCurrentAndLastCompletedStep() throws Exception {
        stubHappyPath(3);
        CountDownLatch stepGate = new CountDownLatch(1);
        when(activities.executeStep(any())).thenAnswer(invocation -> {
            StepExecution execution = invocation.getArgument(0);
            if (execution.index() == 2) {
                stepGate.await(30, TimeUnit.SECONDS);
            }
            return new StepOutcome("out-" + execution.index(), List.of(), List.of(), 5, 1);
        });

        AgentRunWorkflow workflow = start(input("run-query", false));
        AgentRunView during = awaitView(workflow, v -> v.currentStep() == 3
                && !v.steps().isEmpty()
                && v.steps().get(2).status() == StepStatus.RUNNING);

        assertThat(during.currentStep()).isEqualTo(3);
        assertThat(during.lastCompletedStep()).isEqualTo(2);
        assertThat(during.steps().get(2).status()).isEqualTo(StepStatus.RUNNING);

        stepGate.countDown();
        assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("draft");
        assertThat(workflow.view().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(workflow.view().lastCompletedStep()).isEqualTo(3);
    }

    private void stubHappyPath(int stepCount) {
        List<PlanStep> planSteps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            planSteps.add(new PlanStep("RESEARCHER", "Step " + (i + 1)));
        }
        when(activities.prepare(any()))
                .thenReturn(new PreparedRun("goal", new AgentPlan("plan", List.copyOf(planSteps))));
        when(activities.executeStep(any())).thenAnswer(invocation -> {
            StepExecution execution = invocation.getArgument(0);
            return new StepOutcome("out-" + execution.index(), List.of(), List.of(), 5, 1);
        });
        when(activities.write(any())).thenReturn(new DraftOutcome("draft", List.of()));
        when(activities.review(any()))
                .thenReturn(new ReviewOutcome(new Review(true, "ok", List.of()), List.of()));
    }

    private AgentRunWorkflow start(AgentRunInput input) {
        AgentRunWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(
                AgentRunWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId(input.runId())
                        .build());
        WorkflowClient.start(workflow::run, input);
        return workflow;
    }

    private static AgentRunInput input(String runId, boolean requireApproval) {
        return new AgentRunInput(runId, "conv-1", "local", "Compare the reports", null, requireApproval);
    }

    private static AgentRunView awaitView(AgentRunWorkflow workflow, Predicate<AgentRunView> condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        AgentRunView view = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                view = workflow.view();
                if (condition.test(view)) {
                    return view;
                }
            }
            catch (RuntimeException ignored) {
                // query can race workflow startup; retry until the deadline
            }
            Thread.sleep(50);
        }
        return view;
    }
}
