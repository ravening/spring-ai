package com.example.agent.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface AgentRunWorkflow {

    @WorkflowMethod
    String run(AgentRunInput input);

    @QueryMethod
    AgentRunView view();

    @SignalMethod
    void approvePlan(boolean approved);
}
