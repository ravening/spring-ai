package com.example.bank.temporal;

import com.example.bank.bank.BankStore;
import com.example.bank.bank.BankStore.TransferResult;
import com.example.bank.bank.TransferService;
import com.example.bank.bank.TransferService.TransferParams;

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Spring bean backing {@link TransferActivities}. The transfer itself is idempotent in
 * {@link BankStore} (unique {@code idempotency_key}); the key passed by the workflow is
 * stable across retries, so a retried activity returns the original row instead of
 * moving money again.
 */
@Component("transferActivities")
public class TransferActivitiesImpl implements TransferActivities {

    private final TransferService transferService;
    private final BankStore bankStore;

    public TransferActivitiesImpl(TransferService transferService, BankStore bankStore) {
        this.transferService = transferService;
        this.bankStore = bankStore;
    }

    @Override
    public TransferParams validate(TransferRequest request) {
        try {
            return transferService.validate(
                    request.fromUserId(), request.fromAccountId(),
                    request.toUserId(), request.toAccountId(),
                    request.amount(), request.currency(), request.note());
        }
        catch (ResponseStatusException ex) {
            throw nonRetryable(ex);
        }
    }

    @Override
    public TransferReceipt executeTransfer(TransferParams params, String idempotencyKey) {
        String key = StringUtils.hasText(idempotencyKey)
                ? idempotencyKey
                : Activity.getExecutionContext().getInfo().getActivityId();
        try {
            return toReceipt(transferService.transfer(params, key));
        }
        catch (ResponseStatusException ex) {
            throw nonRetryable(ex);
        }
    }

    @Override
    public TransferReceipt reconcile(String idempotencyKey) {
        return bankStore.findByIdempotencyKey(idempotencyKey)
                .map(TransferActivitiesImpl::toReceipt)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "No transfer found for idempotency key " + idempotencyKey, "NotFound"));
    }

    private static ApplicationFailure nonRetryable(ResponseStatusException ex) {
        return ApplicationFailure.newNonRetryableFailure(
                ex.getReason() == null ? ex.getMessage() : ex.getReason(), "ValidationError");
    }

    private static TransferReceipt toReceipt(TransferResult result) {
        return new TransferReceipt(
                result.txnId(), result.fromAccountId(), result.toAccountId(),
                result.amount(), result.currency(),
                result.senderNewBalance(), result.recipientNewBalance(),
                result.duplicate());
    }
}
