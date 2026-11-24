package com.example.agent.temporal;

import java.time.Duration;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.enums.WorkflowIdReusePolicy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper over the auto-configured {@link WorkflowClient}. The workflow ID is the
 * run ID, so a client-supplied run ID plus {@code REJECT_DUPLICATE} makes starting a
 * run idempotent: a retried POST is rejected with 409 instead of starting a second run.
 */
@Service
public class AgentRunService {

    private final WorkflowClient workflowClient;
    private final String taskQueue;
    private final Duration executionTimeout;

    public AgentRunService(WorkflowClient workflowClient,
            @Value("${agent.temporal.run.task-queue:agent-run}") String taskQueue,
            @Value("${agent.temporal.run.execution-timeout:2h}") Duration executionTimeout) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
        this.executionTimeout = executionTimeout;
    }

    public String start(AgentRunInput input, String runId) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(runId)
                .setTaskQueue(taskQueue)
                .setWorkflowExecutionTimeout(executionTimeout)
                .setWorkflowRunTimeout(executionTimeout)
                .setWorkflowTaskTimeout(Duration.ofSeconds(30))
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();

        AgentRunWorkflow workflow = workflowClient.newWorkflowStub(
                AgentRunWorkflow.class, options);

        WorkflowClient.start(workflow::run, input);
        return runId;
    }

    public AgentRunView view(String runId) {
        return stub(runId).view();
    }

    public void approve(String runId, boolean approved) {
        stub(runId).approvePlan(approved);
    }

    public void cancel(String runId) {
        WorkflowStub.fromTyped(stub(runId)).cancel();
    }

    private AgentRunWorkflow stub(String runId) {
        return workflowClient.newWorkflowStub(AgentRunWorkflow.class, runId);
    }
}
