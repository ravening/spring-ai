package com.example.bank.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.example.bank.bank.BankStore.AccountRow;
import com.example.bank.bank.BankStore.TransferResult;
import com.example.bank.jobs.JobProperties;
import com.example.bank.notify.NotificationDtos.NotificationEvent;
import com.example.bank.notify.NotificationService;
import com.example.bank.users.UserDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates and executes real transfers, then emits a single recipient notification
 * carrying the REAL computed balances. Shared by the immediate tool/REST path and the
 * scheduled transfer job executor.
 */
@Service
public class TransferService {

    public record TransferParams(String fromUserId, String fromAccountId, String toUserId, String toAccountId,
            BigDecimal amount, String currency, String note) {
    }

    private final BankStore bankStore;
    private final NotificationService notifications;
    private final UserDirectory users;
    private final JobProperties properties;

    public TransferService(BankStore bankStore, NotificationService notifications,
            UserDirectory users, JobProperties properties) {
        this.bankStore = bankStore;
        this.notifications = notifications;
        this.users = users;
        this.properties = properties;
    }

    /** Validates ownership, currency, amount cap and funds; returns normalized params. */
    public TransferParams validate(String fromUserId, String fromAccountId, String toUserId, String toAccountId,
            String amount, String currency, String note) {
        users.resolve(fromUserId);
        users.resolve(toUserId);
        AccountRow from = bankStore.account(fromAccountId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source account not found"));
        AccountRow to = bankStore.account(toAccountId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destination account not found"));
        if (!from.userId().equals(fromUserId) || !to.userId().equals(toUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account belongs to another user");
        }

        BigDecimal value;
        try {
            value = new BigDecimal(String.valueOf(amount).trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transfer amount");
        }
        if (value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
        if (value.compareTo(properties.maxTransferAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount exceeds the transfer cap of "
                    + properties.maxTransferAmount());
        }

        String effectiveCurrency = currency == null || currency.isBlank() ? from.currency() : currency.trim().toUpperCase();
        if (!from.currency().equalsIgnoreCase(effectiveCurrency) || !to.currency().equalsIgnoreCase(effectiveCurrency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Currency must match both accounts (" + from.currency() + ")");
        }
        if (from.balance().compareTo(value) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient funds");
        }
        String cleanNote = note == null ? "" : note.strip();
        if (cleanNote.length() > 500) {
            cleanNote = cleanNote.substring(0, 500);
        }
        return new TransferParams(fromUserId, fromAccountId, toUserId, toAccountId, value, effectiveCurrency, cleanNote);
    }

    /** Executes a validated transfer and emits the recipient notification. */
    public TransferResult transfer(TransferParams params, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "transfer:" + UUID.randomUUID() : idempotencyKey;
        TransferResult result = bankStore.transfer(
                params.fromUserId(), params.fromAccountId(), params.toUserId(), params.toAccountId(),
                params.amount(), params.currency(), params.note(), key);

        String fromName = users.resolve(params.fromUserId()).displayName();
        String symbol = switch (params.currency()) {
            case "EUR" -> "€";
            case "USD" -> "$";
            case "GBP" -> "£";
            default -> params.currency() + " ";
        };
        String amountText = symbol + params.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String oldText = symbol + result.recipientOldBalance().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String newText = symbol + result.recipientNewBalance().setScale(2, RoundingMode.HALF_UP).toPlainString();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("amount", params.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        detail.put("currency", params.currency());
        detail.put("from", fromName);
        detail.put("txnId", result.txnId());
        detail.put("oldBalance", result.recipientOldBalance().setScale(2, RoundingMode.HALF_UP).toPlainString());
        detail.put("newBalance", result.recipientNewBalance().setScale(2, RoundingMode.HALF_UP).toPlainString());
        if (params.note() != null && !params.note().isBlank()) {
            detail.put("note", params.note());
        }

        String message = "💸 Incoming transfer of " + amountText + " from **" + fromName + "**.  \n"
                + "Transaction `" + result.txnId() + "`.  \n"
                + "Balance: " + oldText + " → " + newText + "."
                + (params.note() == null || params.note().isBlank() ? "" : "  \nNote: " + params.note());

        notifications.emit(new NotificationEvent(
                params.toUserId(),
                notifications.recipientConversation(params.toUserId()),
                "transfer_received",
                "Incoming transfer",
                "You received a transfer from " + fromName,
                NotificationService.json(detail),
                null,
                "transfer:" + result.txnId(),
                message));
        return result;
    }

    /** Convenience used by the immediate REST endpoint and agent tool. */
    public TransferResult transfer(String fromUserId, String fromAccountId, String toUserId, String toAccountId,
            String amount, String currency, String note, String idempotencyKey) {
        return transfer(validate(fromUserId, fromAccountId, toUserId, toAccountId, amount, currency, note),
                idempotencyKey);
    }
}
