package com.example.bank.tools;

import java.math.RoundingMode;

import com.example.bank.bank.TransferService;
import com.example.bank.bank.BankStore.TransferResult;
import com.example.bank.core.PromptSafety;
import com.example.bank.ratelimit.RateLimitProperties;
import com.example.bank.ratelimit.RateLimiter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Immediate real-money transfer through the same validated path as scheduled jobs. */
@Component
public class TransferTools {

    private final TransferService transfers;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public TransferTools(TransferService transfers, RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties) {
        this.transfers = transfers;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Tool(name = "transfer_money", description = """
            Execute an immediate real transfer between two bank accounts. Validates account
            ownership, currency, the amount cap and available funds, moves the money, writes an
            audit record and notifies the recipient. The userId and conversationId are injected
            automatically; do not set them manually.""")
    public String transferMoney(
            @ToolParam(description = "Source account id of the current user, e.g. ACC-ALICE-CHECK", required = true) String fromAccountId,
            @ToolParam(description = "Destination user id, e.g. bob", required = true) String toUserId,
            @ToolParam(description = "Destination account id, e.g. ACC-BOB-CHECK", required = true) String toAccountId,
            @ToolParam(description = "Positive amount to transfer, e.g. 10.00", required = true) String amount,
            @ToolParam(description = "Currency code matching both accounts, e.g. EUR or USD", required = false) String currency,
            @ToolParam(description = "Optional payment reference", required = false) String note,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String userId,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String conversationId) {

        try {
            rateLimiter.check("transfers", userId, rateLimitProperties.transfersPerMinute());
            TransferResult result = transfers.transfer(userId, fromAccountId, toUserId, toAccountId,
                    amount, currency, note, null);
            String value = result.amount().setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + result.currency();
            return PromptSafety.data("Transfer completed.\n"
                    + "Transaction: " + result.txnId() + "\n"
                    + "Amount: " + value + "\n"
                    + "Sender balance: " + result.senderOldBalance() + " -> " + result.senderNewBalance() + "\n"
                    + "Recipient balance: " + result.recipientOldBalance() + " -> " + result.recipientNewBalance());
        } catch (Exception ex) {
            return PromptSafety.data("Transfer failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }
}
