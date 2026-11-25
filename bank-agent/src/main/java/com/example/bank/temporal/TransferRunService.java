package com.example.bank.temporal;

import java.time.Duration;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper over the auto-configured {@link WorkflowClient}. The workflow ID is the
 * run ID; {@code REJECT_DUPLICATE} makes a retried start a 409 instead of a second run.
 */
@Service
public class TransferRunService {

    private final WorkflowClient workflowClient;
    private final String taskQueue;
    private final Duration executionTimeout;

    public TransferRunService(WorkflowClient workflowClient,
            @Value("${bank.temporal.run.task-queue:transfer-run}") String taskQueue,
            @Value("${bank.temporal.run.execution-timeout:1h}") Duration executionTimeout) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
        this.executionTimeout = executionTimeout;
    }

    public String start(TransferRequest request, String runId) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(runId)
                .setTaskQueue(taskQueue)
                .setWorkflowExecutionTimeout(executionTimeout)
                .setWorkflowRunTimeout(executionTimeout)
                .setWorkflowTaskTimeout(Duration.ofSeconds(30))
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();

        TransferWorkflow workflow = workflowClient.newWorkflowStub(
                TransferWorkflow.class, options);

        WorkflowClient.start(workflow::run, request);
        return runId;
    }

    public TransferView view(String runId) {
        return workflowClient.newWorkflowStub(TransferWorkflow.class, runId).view();
    }

    public void approve(String runId, boolean approved) {
        workflowClient.newWorkflowStub(TransferWorkflow.class, runId).approve(approved);
    }
}
