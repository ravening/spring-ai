package com.example.bank.jobs;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.bank.bank.BankStore.TransferResult;
import com.example.bank.bank.TransferService;
import com.example.bank.bank.TransferService.TransferParams;
import com.example.bank.jobs.JobDtos.JobResult;
import com.example.bank.jobs.JobDtos.JobRow;
import com.example.bank.users.UserDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Scheduled transfer: validates and delegates to the same TransferService as immediate transfers. */
@Component
public class TransferJobExecutor implements JobExecutor {

    private final TransferService transfers;
    private final UserDirectory users;
    private final ObjectMapper mapper = new ObjectMapper();

    public TransferJobExecutor(TransferService transfers, UserDirectory users) {
        this.transfers = transfers;
        this.users = users;
    }

    @Override
    public String type() {
        return "transfer";
    }

    @Override
    public void validate(String payload, String userId) {
        params(payload, userId);
    }

    @Override
    public JobResult execute(JobRow job) {
        TransferParams params = params(job.payload(), job.userId());
        // Idempotency key includes the scheduled run time: a retried/duplicated run
        // of this occurrence can never move money twice.
        TransferResult result = transfers.transfer(params, "job:" + job.id() + ":run:" + job.runAt());

        String fromName = users.resolve(params.fromUserId()).displayName();
        String amountText = params.amount().setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + params.currency();
        String oldBalance = result.recipientOldBalance().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String newBalance = result.recipientNewBalance().setScale(2, RoundingMode.HALF_UP).toPlainString();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("amount", params.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        detail.put("currency", params.currency());
        detail.put("from", fromName);
        detail.put("txnId", result.txnId());
        detail.put("oldBalance", oldBalance);
        detail.put("newBalance", newBalance);
        detail.put("jobDescription", "Scheduled transfer");

        String summary = "Transfer of " + amountText + " to " + params.toUserId() + " completed";
        String message = "💸 Scheduled transfer of **" + amountText + "** to **" + fromNameForRecipient(params)
                + "** completed.  \nTransaction `" + result.txnId() + "`.  \n"
                + "Recipient balance: " + oldBalance + " → " + newBalance + ".";
        return new JobResult(summary, message, JobService.json(detail));
    }

    private String fromNameForRecipient(TransferParams params) {
        return users.resolve(params.toUserId()).displayName();
    }

    private TransferParams params(String payload, String userId) {
        JsonNode node;
        try {
            node = mapper.readTree(payload == null ? "" : payload);
        } catch (Exception ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid transfer job payload");
        }
        return transfers.validate(userId,
                text(node, "fromAccountId"), text(node, "toUserId"), text(node, "toAccountId"),
                text(node, "amount"), text(node, "currency"), text(node, "note"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
