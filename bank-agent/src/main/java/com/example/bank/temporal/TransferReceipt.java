package com.example.bank.temporal;

import java.math.BigDecimal;

/** Result of a durable transfer run. {@code duplicate} is true when the ledger already had the idempotency key. */
public record TransferReceipt(
        String txnId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        String currency,
        BigDecimal senderNewBalance,
        BigDecimal recipientNewBalance,
        boolean duplicate) {
}
