package com.example.bank.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TransferWorkflow {

    @WorkflowMethod
    TransferReceipt run(TransferRequest request);

    @QueryMethod
    TransferView view();

    @SignalMethod
    void approve(boolean approved);
}
