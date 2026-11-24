package com.example.bank.temporal;

/** Input of one durable transfer run. The workflow ID equals {@code runId}. */
public record TransferRequest(
        String runId,
        String fromUserId,
        String fromAccountId,
        String toUserId,
        String toAccountId,
        String amount,
        String currency,
        String note,
        boolean requireApproval) {
}
