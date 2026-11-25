package com.example.bank.temporal;

import com.example.bank.bank.TransferService.TransferParams;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface TransferActivities {

    /** Read-only validation: accounts, ownership, limits, funds. Returns normalized params. */
    TransferParams validate(TransferRequest request);

    /**
     * Executes the transfer under the given idempotency key and notifies the recipient
     * (the notification dedupe key suppresses a second notification on replay).
     */
    TransferReceipt executeTransfer(TransferParams params, String idempotencyKey);

    /** Looks up a previously committed transfer by idempotency key after an unknown outcome. */
    TransferReceipt reconcile(String idempotencyKey);
}
