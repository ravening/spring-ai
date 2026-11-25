package com.example.bank.temporal;

import java.math.BigDecimal;
import java.util.function.Predicate;

import com.example.bank.bank.TransferService.TransferParams;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transfer workflow tests against an in-process Temporal test server with mocked
 * activities. Covers approval, rejection, the unknown-outcome reconcile path and
 * non-retryable validation failures.
 */
class TransferWorkflowTest {

    private static final String TASK_QUEUE = "transfer-run";

    private TestWorkflowEnvironment testEnv;
    private TransferActivities activities;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(TransferWorkflowImpl.class);
        activities = mock(TransferActivities.class);
        worker.registerActivitiesImplementations(activities);
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void approvalThenTransferCompletes() throws Exception {
        when(activities.validate(any())).thenReturn(params());
        when(activities.executeTransfer(any(), anyString())).thenReturn(receipt(false));

        TransferWorkflow workflow = start(request("run-approve", true));
        TransferView waiting = awaitView(workflow, v -> v.status() == TransferView.Status.WAITING_APPROVAL);
        assertThat(waiting.status()).isEqualTo(TransferView.Status.WAITING_APPROVAL);
        verify(activities, never()).executeTransfer(any(), anyString());

        workflow.approve(true);
        TransferReceipt result = WorkflowStub.fromTyped(workflow).getResult(TransferReceipt.class);

        assertThat(result.txnId()).isEqualTo("TXN-ABC123");
        verify(activities, times(1)).executeTransfer(any(), argThat("run-approve:transfer"::equals));
        TransferView view = workflow.view();
        assertThat(view.status()).isEqualTo(TransferView.Status.COMPLETED);
        assertThat(view.receipt().txnId()).isEqualTo("TXN-ABC123");
    }

    @Test
    void rejectedApprovalCancelsWithoutTransfer() throws Exception {
        when(activities.validate(any())).thenReturn(params());

        TransferWorkflow workflow = start(request("run-reject", true));
        awaitView(workflow, v -> v.status() == TransferView.Status.WAITING_APPROVAL);

        workflow.approve(false);
        TransferReceipt result = WorkflowStub.fromTyped(workflow).getResult(TransferReceipt.class);

        assertThat(result).isNull();
        verify(activities, never()).executeTransfer(any(), anyString());
        assertThat(workflow.view().status()).isEqualTo(TransferView.Status.CANCELLED);
    }

    @Test
    void unknownOutcomeAfterCommitIsReconciledAgainstTheLedger() {
        when(activities.validate(any())).thenReturn(params());
        when(activities.executeTransfer(any(), anyString()))
                .thenThrow(new RuntimeException("connection lost after commit"));
        when(activities.reconcile(anyString())).thenReturn(receipt(true));

        TransferWorkflow workflow = start(request("run-reconcile", false));
        TransferReceipt result = WorkflowStub.fromTyped(workflow).getResult(TransferReceipt.class);

        assertThat(result.duplicate()).isTrue();
        verify(activities, times(3)).executeTransfer(any(), argThat("run-reconcile:transfer"::equals));
        verify(activities, times(1)).reconcile(argThat("run-reconcile:transfer"::equals));
        assertThat(workflow.view().status()).isEqualTo(TransferView.Status.COMPLETED);
    }

    @Test
    void nonRetryableValidationFailureFailsTheWorkflow() {
        when(activities.validate(any())).thenThrow(
                ApplicationFailure.newNonRetryableFailure("Insufficient funds", "ValidationError"));

        TransferWorkflow workflow = start(request("run-invalid", false));

        assertThatThrownBy(() -> WorkflowStub.fromTyped(workflow).getResult(TransferReceipt.class))
                .isInstanceOf(WorkflowFailedException.class);
        verify(activities, times(1)).validate(any());
        verify(activities, never()).executeTransfer(any(), anyString());
        TransferView view = workflow.view();
        assertThat(view.status()).isEqualTo(TransferView.Status.FAILED);
        assertThat(view.error()).contains("Insufficient funds");
    }

    private TransferWorkflow start(TransferRequest request) {
        TransferWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(
                TransferWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId(request.runId())
                        .build());
        WorkflowClient.start(workflow::run, request);
        return workflow;
    }

    private static TransferRequest request(String runId, boolean requireApproval) {
        return new TransferRequest(runId, "alice", "ACC-ALICE-CHECK", "bob", "ACC-BOB-CHECK",
                "10.00", "EUR", "rent", requireApproval);
    }

    private static TransferParams params() {
        return new TransferParams("alice", "ACC-ALICE-CHECK", "bob", "ACC-BOB-CHECK",
                new BigDecimal("10.00"), "EUR", "rent");
    }

    private static TransferReceipt receipt(boolean duplicate) {
        return new TransferReceipt("TXN-ABC123", "ACC-ALICE-CHECK", "ACC-BOB-CHECK",
                new BigDecimal("10.00"), "EUR", new BigDecimal("90.00"), new BigDecimal("110.00"), duplicate);
    }

    private static TransferView awaitView(TransferWorkflow workflow, Predicate<TransferView> condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        TransferView view = null;
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
