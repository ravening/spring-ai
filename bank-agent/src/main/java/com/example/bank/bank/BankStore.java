package com.example.bank.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.bank.data.BankAccount;
import com.example.bank.data.BankData;
import com.example.bank.users.BankUser;
import com.example.bank.users.UserDirectory;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authoritative H2 ledger. Accounts are seeded ONCE from the deterministic BankData
 * on first boot, so later balance mutations survive restarts. Every transfer moves
 * real money between accounts under per-account locks and is fully audited, with a
 * unique idempotency key so a retry cannot double-move funds.
 */
@Repository
public class BankStore {

    public record AccountRow(String accountId, String userId, String name, String type, String currency,
            BigDecimal balance, String iban) {
    }

    public record TransferResult(String txnId, String fromAccountId, String toAccountId, BigDecimal amount,
            String currency, BigDecimal senderOldBalance, BigDecimal senderNewBalance,
            BigDecimal recipientOldBalance, BigDecimal recipientNewBalance, boolean duplicate) {
    }

    public record TransferRow(String id, String fromUserId, String fromAccountId, String toUserId,
            String toAccountId, BigDecimal amount, String currency, String note,
            BigDecimal fromOldBalance, BigDecimal fromNewBalance,
            BigDecimal toOldBalance, BigDecimal toNewBalance, long createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final BankData bankData;
    private final UserDirectory users;
    private final ConcurrentHashMap<String, Object> accountLocks = new ConcurrentHashMap<>();

    public BankStore(JdbcTemplate jdbc, BankData bankData, UserDirectory users) {
        this.jdbc = jdbc;
        this.bankData = bankData;
        this.users = users;
    }

    @PostConstruct
    public void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bank_accounts (
                    account_id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    name VARCHAR(255),
                    type VARCHAR(32),
                    currency VARCHAR(8) NOT NULL,
                    balance DECIMAL(19,2) NOT NULL,
                    iban VARCHAR(64)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bank_transfers (
                    id VARCHAR(64) PRIMARY KEY,
                    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
                    from_user_id VARCHAR(64) NOT NULL,
                    from_account_id VARCHAR(64) NOT NULL,
                    to_user_id VARCHAR(64) NOT NULL,
                    to_account_id VARCHAR(64) NOT NULL,
                    amount DECIMAL(19,2) NOT NULL,
                    currency VARCHAR(8) NOT NULL,
                    note VARCHAR(500),
                    from_old_balance DECIMAL(19,2) NOT NULL,
                    from_new_balance DECIMAL(19,2) NOT NULL,
                    to_old_balance DECIMAL(19,2) NOT NULL,
                    to_new_balance DECIMAL(19,2) NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS bank_meta (k VARCHAR(64) PRIMARY KEY, v VARCHAR(255))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bank_transfers_users ON bank_transfers (from_user_id, to_user_id)");
        seedOnce();
    }

    private synchronized void seedOnce() {
        Long seeded = jdbc.queryForObject("SELECT COUNT(*) FROM bank_meta WHERE k = 'seeded'", Long.class);
        if (seeded != null && seeded > 0) {
            return;
        }
        for (BankUser user : users.list()) {
            for (BankAccount account : bankData.accounts(user.id())) {
                jdbc.update("""
                        MERGE INTO bank_accounts (account_id, user_id, name, type, currency, balance, iban)
                        KEY(account_id) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, account.accountId(), user.id(), account.name(), account.type(), account.currency(),
                        account.balance(), account.iban());
            }
        }
        jdbc.update("MERGE INTO bank_meta (k, v) KEY(k) VALUES ('seeded', '1')");
    }

    public List<AccountRow> accounts(String userId) {
        return jdbc.query("""
                SELECT account_id, user_id, name, type, currency, balance, iban
                FROM bank_accounts WHERE user_id = ? ORDER BY account_id
                """, (rs, rowNum) -> new AccountRow(
                        rs.getString("account_id"), rs.getString("user_id"), rs.getString("name"),
                        rs.getString("type"), rs.getString("currency"), rs.getBigDecimal("balance"),
                        rs.getString("iban")), userId);
    }

    public Optional<AccountRow> account(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT account_id, user_id, name, type, currency, balance, iban
                        FROM bank_accounts WHERE account_id = ?
                        """, (rs, rowNum) -> new AccountRow(
                                rs.getString("account_id"), rs.getString("user_id"), rs.getString("name"),
                                rs.getString("type"), rs.getString("currency"), rs.getBigDecimal("balance"),
                                rs.getString("iban")), accountId.trim())
                .stream().findFirst();
    }

    public List<TransferRow> transfers(String userId) {
        return jdbc.query("""
                SELECT id, from_user_id, from_account_id, to_user_id, to_account_id, amount, currency, note,
                       from_old_balance, from_new_balance, to_old_balance, to_new_balance, created_at
                FROM bank_transfers WHERE from_user_id = ? OR to_user_id = ? ORDER BY created_at DESC LIMIT 200
                """, (rs, rowNum) -> new TransferRow(
                        rs.getString("id"), rs.getString("from_user_id"), rs.getString("from_account_id"),
                        rs.getString("to_user_id"), rs.getString("to_account_id"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("note"), rs.getBigDecimal("from_old_balance"),
                        rs.getBigDecimal("from_new_balance"), rs.getBigDecimal("to_old_balance"),
                        rs.getBigDecimal("to_new_balance"), rs.getLong("created_at")), userId, userId);
    }

    /**
     * Moves money under deterministic account locking and returns REAL old/new
     * balances. A repeated idempotency key returns the original result unchanged.
     */
    public TransferResult transfer(String fromUserId, String fromAccountId, String toUserId, String toAccountId,
            BigDecimal amount, String currency, String note, String idempotencyKey) {
        Optional<TransferResult> existing = findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        String first = fromAccountId.compareTo(toAccountId) <= 0 ? fromAccountId : toAccountId;
        String second = first.equals(fromAccountId) ? toAccountId : fromAccountId;
        synchronized (lockFor(first)) {
            synchronized (lockFor(second)) {
                existing = findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    return existing.get();
                }
                AccountRow from = account(fromAccountId).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source account not found"));
                AccountRow to = account(toAccountId).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destination account not found"));
                if (!from.userId().equals(fromUserId) || !to.userId().equals(toUserId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account belongs to another user");
                }
                BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
                if (from.balance().compareTo(value) < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient funds");
                }

                BigDecimal senderNew = from.balance().subtract(value).setScale(2, RoundingMode.HALF_UP);
                BigDecimal recipientNew = to.balance().add(value).setScale(2, RoundingMode.HALF_UP);
                String txnId = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                long now = System.currentTimeMillis();

                jdbc.update("UPDATE bank_accounts SET balance = ? WHERE account_id = ?", senderNew, fromAccountId);
                if (!fromAccountId.equals(toAccountId)) {
                    jdbc.update("UPDATE bank_accounts SET balance = ? WHERE account_id = ?", recipientNew, toAccountId);
                }
                try {
                    jdbc.update("""
                            INSERT INTO bank_transfers
                                (id, idempotency_key, from_user_id, from_account_id, to_user_id, to_account_id,
                                 amount, currency, note, from_old_balance, from_new_balance,
                                 to_old_balance, to_new_balance, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, txnId, idempotencyKey, fromUserId, fromAccountId, toUserId, toAccountId,
                            value, currency, note, from.balance(), senderNew,
                            fromAccountId.equals(toAccountId) ? from.balance() : to.balance(),
                            fromAccountId.equals(toAccountId) ? from.balance() : recipientNew, now);
                } catch (DuplicateKeyException ex) {
                    return findByIdempotencyKey(idempotencyKey).orElseThrow();
                }
                return new TransferResult(txnId, fromAccountId, toAccountId, value, currency,
                        from.balance(), senderNew,
                        fromAccountId.equals(toAccountId) ? from.balance() : to.balance(),
                        fromAccountId.equals(toAccountId) ? from.balance() : recipientNew, false);
            }
        }
    }

    /** Public so the Temporal reconcile activity can look up a transfer by idempotency key. */
    public Optional<TransferResult> findByIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT id, from_account_id, to_account_id, amount, currency,
                               from_old_balance, from_new_balance, to_old_balance, to_new_balance
                        FROM bank_transfers WHERE idempotency_key = ?
                        """, (rs, rowNum) -> new TransferResult(
                                rs.getString("id"), rs.getString("from_account_id"), rs.getString("to_account_id"),
                                rs.getBigDecimal("amount"), rs.getString("currency"),
                                rs.getBigDecimal("from_old_balance"), rs.getBigDecimal("from_new_balance"),
                                rs.getBigDecimal("to_old_balance"), rs.getBigDecimal("to_new_balance"), true),
                        key).stream().findFirst();
    }

    private Object lockFor(String accountId) {
        return accountLocks.computeIfAbsent(accountId, id -> new Object());
    }
}
