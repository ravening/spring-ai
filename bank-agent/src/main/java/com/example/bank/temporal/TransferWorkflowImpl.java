package com.example.bank.temporal;

import java.time.Duration;

import com.example.bank.bank.TransferService.TransferParams;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * One Temporal workflow per durable transfer. Deterministic orchestration only: the
 * ledger write happens in the {@code executeTransfer} activity under a stable
 * idempotency key, so a crash after the commit but before the activity returns does
 * not move money twice — the retry (or the reconcile activity after an unknown
 * outcome) reads the original row back.
 */
@WorkflowImpl(workers = "transfer-run")
public class TransferWorkflowImpl implements TransferWorkflow {

    private static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);

    private final TransferActivities activities = Workflow.newActivityStub(
            TransferActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setHeartbeatTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setMaximumAttempts(3)
                            .setDoNotRetry(IllegalArgumentException.class.getName())
                            .build())
                    .build());

    private TransferView.Status status = TransferView.Status.PENDING;
    private String step = "start";
    private Boolean approval;
    private TransferReceipt receipt;
    private String error;

    @Override
    public TransferReceipt run(TransferRequest request) {
        try {
            status = TransferView.Status.VALIDATING;
            step = "validate";
            TransferParams params = activities.validate(request);

            if (request.requireApproval()) {
                status = TransferView.Status.WAITING_APPROVAL;
                step = "approval";
                boolean answered = Workflow.await(APPROVAL_TIMEOUT, () -> approval != null);
                if (!answered || !Boolean.TRUE.equals(approval)) {
                    status = TransferView.Status.CANCELLED;
                    return null;
                }
            }

            status = TransferView.Status.EXECUTING;
            step = "transfer";
            // Stable across activity retries and worker restarts: derived from the workflow ID.
            String idempotencyKey = Workflow.getInfo().getWorkflowId() + ":transfer";
            try {
                receipt = activities.executeTransfer(params, idempotencyKey);
            }
            catch (ActivityFailure failure) {
                if (isNonRetryable(failure)) {
                    throw failure;
                }
                // Unknown outcome (e.g. timeout after the commit): reconcile against the ledger.
                status = TransferView.Status.NEEDS_RECONCILIATION;
                step = "reconcile";
                receipt = activities.reconcile(idempotencyKey);
            }

            status = TransferView.Status.COMPLETED;
            return receipt;
        }
        catch (Exception ex) {
            error = rootMessage(ex);
            status = TransferView.Status.FAILED;
            throw ex;
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null ? message : ex.getMessage();
    }

    @Override
    public TransferView view() {
        return new TransferView(Workflow.getInfo().getWorkflowId(), status, step, error, receipt);
    }

    @Override
    public void approve(boolean approved) {
        this.approval = approved;
    }

    private static boolean isNonRetryable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApplicationFailure applicationFailure && applicationFailure.isNonRetryable()) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
