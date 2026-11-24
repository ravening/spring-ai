package com.example.bank.temporal;

/** Query result of a durable transfer run. */
public record TransferView(
        String runId,
        Status status,
        String step,
        String error,
        TransferReceipt receipt) {

    public enum Status {
        PENDING,
        VALIDATING,
        WAITING_APPROVAL,
        EXECUTING,
        NEEDS_RECONCILIATION,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
