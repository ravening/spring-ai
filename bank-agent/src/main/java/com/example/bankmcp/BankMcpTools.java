package com.example.bankmcp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import com.example.bank.data.BankAccount;
import com.example.bank.data.BankCard;
import com.example.bank.data.BankData;
import com.example.bank.data.BankTransaction;
import com.example.bank.data.IncomeSummary;
import com.example.bank.data.SpendingSummary;
import com.example.bank.users.UserDirectory;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Banking tools exposed over MCP. The agent app never touches bank data directly;
 * every banking fact comes from these tools. All tools are scoped by userId so one
 * customer can never read another customer's data.
 */
@Component
public class BankMcpTools {

    private final BankData data;

    public BankMcpTools() {
        this.data = new BankData(new UserDirectory());
    }

    @McpTool(name = "list_accounts",
            description = "List all bank accounts of a customer: id, name, type, currency, current balance and IBAN.")
    public String listAccounts(
            @McpToolParam(description = "Customer user id, for example alice", required = true) String userId) {
        StringBuilder sb = new StringBuilder("Accounts for user '" + userId + "':\n");
        for (BankAccount account : data.accounts(userId)) {
            sb.append("- ").append(account.accountId())
                    .append(" | ").append(account.name())
                    .append(" | type=").append(account.type())
                    .append(" | currency=").append(account.currency())
                    .append(" | balance=").append(account.balance()).append(' ').append(account.currency())
                    .append(" | iban=").append(account.iban())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    @McpTool(name = "get_account_balance",
            description = "Get the current balance of one account, or of all accounts when accountId is omitted.")
    public String getAccountBalance(
            @McpToolParam(description = "Customer user id, for example alice", required = true) String userId,
            @McpToolParam(description = "Account id such as ACC-ALICE-CHECK. Omit for all accounts.", required = false) String accountId) {
        if (accountId != null && !accountId.isBlank()) {
            return data.account(userId, accountId)
                    .map(account -> account.accountId() + " (" + account.name() + "): balance="
                            + account.balance() + " " + account.currency())
                    .orElseGet(() -> "Account '" + accountId + "' was not found for user '" + userId + "'.");
        }
        StringBuilder sb = new StringBuilder("Balances for user '" + userId + "':\n");
        for (BankAccount account : data.accounts(userId)) {
            sb.append("- ").append(account.accountId()).append(" (").append(account.name()).append("): ")
                    .append(account.balance()).append(' ').append(account.currency()).append('\n');
        }
        return sb.toString().trim();
    }

    @McpTool(name = "list_transactions",
            description = "List transactions for a customer, newest first. Optional filters: accountId, from/to date (yyyy-MM-dd), category, and limit (default 20, max 100). Use this for questions like 'show my last 10 transactions'.")
    public String listTransactions(
            @McpToolParam(description = "Customer user id, for example alice", required = true) String userId,
            @McpToolParam(description = "Account id to filter by. Omit for all accounts.", required = false) String accountId,
            @McpToolParam(description = "Start date inclusive, yyyy-MM-dd.", required = false) String from,
            @McpToolParam(description = "End date inclusive, yyyy-MM-dd.", required = false) String to,
            @McpToolParam(description = "Category filter such as GROCERIES, DINING, INCOME.", required = false) String category,
            @McpToolParam(description = "Maximum number of transactions to return (default 20, max 100).", required = false) Integer limit) {
        LocalDate fromDate = safeDate(from);
        LocalDate toDate = safeDate(to);
        int max = limit == null ? 20 : limit;
        List<BankTransaction> txns = data.transactions(userId, accountId, fromDate, toDate, category, max);
        if (txns.isEmpty()) {
            return "No transactions matched the given filters for user '" + userId + "'.";
        }
        StringBuilder sb = new StringBuilder("Transactions for user '" + userId + "' (showing " + txns.size() + "):\n");
        for (BankTransaction txn : txns) {
            sb.append("- ").append(txn.date())
                    .append(" | ").append(txn.transactionId())
                    .append(" | ").append(txn.amount()).append(' ').append(txn.currency())
                    .append(" | ").append(txn.merchant())
                    .append(" | category=").append(txn.category())
                    .append(" | account=").append(txn.accountId())
                    .append(" | ").append(txn.description())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    @McpTool(name = "search_transactions",
            description = "Search transactions of a customer by keyword in merchant, description, category or account.")
    public String searchTransactions(
            @McpToolParam(description = "Customer user id, for example alice", required = true) String userId,
            @McpToolParam(description = "Keyword to search for, for example 'amazon' or 'salary'", required = true) String keyword,
            @McpToolParam(description = "Maximum number of results (default 10, max 50).", required = false) Integer limit) {
        List<BankTransaction> txns = data.search(userId, keyword, limit == null ? 10 : limit);
        if (txns.isEmpty()) {
            return "No transactions matched '" + keyword + "' for user '" + userId + "'.";
        }
        StringBuilder sb = new StringBuilder("Transactions matching '" + keyword + "' for user '" + userId + "':\n");
        for (BankTransaction txn : txns) {
            sb.append("- ").append(txn.date())
                    .append(" | ").append(txn.amount()).append(' ').append(txn.currency())
                    .append(" | ").append(txn.merchant())
                    .append(" | category=").append(txn.category())
                    .append(" | ").append(txn.description())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    @McpTool(name = "spending_summary",
            description = "Summarize spending (money out) of a customer for a month, overall and per category. month is yyyy-MM, default is the current month.")
    public String spendingSummary(
            @McpToolParam(description = "Customer user id, for example alice", required = true) String userId,
            @McpToolParam(description = "Month as yyyy-MM, for example 2026-05. Defaults to the current month.", required = false) String month,
            @McpToolParam(description = "Account id to filter by. Omit for all accounts.", required = false) String accountId) {
        YearMonth target = safeMonth(month);
        SpendingSummary summary = data.spendingSummary(userId, target, accountId);
        StringBuilder sb = new StringBuilder("Spending summary for user '" + userId + "' in " + target + ":\n");
        sb.append("- total spent: ").append(summary.total()).append(' ').append(summary.currency()).append('\n');
        sb.append("- transactions: ").append(summary.transactionCount()).append('\n');
        sb.append("- by category:\n");
        if (summary.byCategory().isEmpty()) {
            sb.append("  (no spending recorded)\n");
        }
        for (Map.Entry<String, BigDecimal> entry : summary.byCategory().entrySet()) {
            sb.append("  - ").append(entry.getKey()).append(": ")
                    .append(entry.getValue()).append(' ').append(summary.currency()).append('\n');
        }
        return sb.toString().trim();
    }

    @McpTool(name = "income_summary",
            description = "Summarize income (money in) of a customer for a month, overall and per source. month is yyyy-MM, default is the current month.")
    public String incomeSummary(
            @McpToolParam(description = "Customer user id, for example alice", required = true) String userId,
            @McpToolParam(description = "Month as yyyy-MM, for example 2026-05. Defaults to the current month.", required = false) String month,
            @McpToolParam(description = "Account id to filter by. Omit for all accounts.", required = false) String accountId) {
        YearMonth target = safeMonth(month);
        IncomeSummary summary = data.incomeSummary(userId, target, accountId);
        StringBuilder sb = new StringBuilder("Income summary for user '" + userId + "' in " + target + ":\n");
        sb.append("- total income: ").append(summary.total()).append(' ').append(summary.currency()).append('\n');
        sb.append("- transactions: ").append(summary.transactionCount()).append('\n');
        sb.append("- by source:\n");
        if (summary.bySource().isEmpty()) {
            sb.append("  (no income recorded)\n");
        }
        for (Map.Entry<String, BigDecimal> entry : summary.bySource().entrySet()) {
            sb.append("  - ").append(entry.getKey()).append(": ")
                    .append(entry.getValue()).append(' ').append(summary.currency()).append('\n');
        }
        return sb.toString().trim();
    }

    @McpTool(name = "list_cards",
            description = "List the debit/credit cards of a customer with masked number, status, monthly limit and amount spent this month.")
    public String listCards(
            @McpToolParam(description = "Customer user id, for example alice", required = true) String userId) {
        List<BankCard> cards = data.cards(userId);
        if (cards.isEmpty()) {
            return "User '" + userId + "' has no cards.";
        }
        StringBuilder sb = new StringBuilder("Cards for user '" + userId + "':\n");
        for (BankCard card : cards) {
            sb.append("- ").append(card.cardId())
                    .append(" | ").append(card.maskedNumber())
                    .append(" | type=").append(card.type())
                    .append(" | status=").append(card.status())
                    .append(" | monthly limit=").append(card.monthlyLimit())
                    .append(" | spent this month=").append(card.spentThisMonth())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    @McpTool(name = "get_customer_profile",
            description = "Get the customer profile: display name, segment, home branch, number of accounts, total balance per currency and number of cards.")
    public String getCustomerProfile(
            @McpToolParam(description = "Customer user id, for example alice", required = true) String userId) {
        return data.profile(userId);
    }

    private static LocalDate safeDate(String value) {
        try {
            return BankData.parseDate(value);
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static YearMonth safeMonth(String value) {
        try {
            return BankData.parseMonth(value);
        }
        catch (Exception ex) {
            return YearMonth.now();
        }
    }
}
