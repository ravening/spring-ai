package com.example.bank.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import com.example.bank.users.UserDirectory;

import org.springframework.stereotype.Component;

/**
 * Deterministic in-memory demo data for the seeded bank customers. Data is generated
 * from a per-user seed, so every restart produces the same accounts, transactions and
 * cards. Balances are derived from the transactions so they stay consistent.
 */
@Component
public class BankData {

    private static final Map<String, String[]> MERCHANTS = Map.of(
            "GROCERIES", new String[] { "Rewe", "Edeka", "Aldi Sued", "Lidl" },
            "DINING", new String[] { "Cafe Kranzler", "Restaurant Lutter", "Burgermeister", "Vapiano", "Imren Grill" },
            "TRANSPORT", new String[] { "BVG", "Deutsche Bahn", "Shell", "Aral", "Sixt" },
            "UTILITIES", new String[] { "Stadtwerke", "Vattenfall", "Telekom", "Vodafone" },
            "ENTERTAINMENT", new String[] { "Netflix", "Spotify", "CinemaXX", "Steam" },
            "SHOPPING", new String[] { "Amazon", "Zalando", "IKEA", "MediaMarkt" },
            "HEALTH", new String[] { "Apotheke am Ring", "Dr. med. Sommer", "Fielmann" },
            "SUBSCRIPTIONS", new String[] { "Fitness First", "ADAC", "Medium" },
            "TRANSFER", new String[] { "Internal transfer", "Savings deposit" });

    private static final String[] SPEND_CATEGORIES = {
            "GROCERIES", "GROCERIES", "DINING", "DINING", "TRANSPORT", "UTILITIES",
            "ENTERTAINMENT", "SHOPPING", "SHOPPING", "HEALTH", "SUBSCRIPTIONS", "TRANSFER" };

    private final UserDirectory users;
    private final Map<String, List<BankAccount>> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<BankTransaction>> transactions = new ConcurrentHashMap<>();
    private final Map<String, List<BankCard>> cards = new ConcurrentHashMap<>();

    public BankData(UserDirectory users) {
        this.users = users;
        users.list().forEach(user -> seed(user.id()));
    }

    public List<BankAccount> accounts(String userId) {
        requireKnownUser(userId);
        return List.copyOf(accounts.getOrDefault(userId, List.of()));
    }

    public Optional<BankAccount> account(String userId, String accountId) {
        return accounts(userId).stream()
                .filter(account -> account.accountId().equalsIgnoreCase(accountId == null ? "" : accountId.trim()))
                .findFirst();
    }

    public List<BankTransaction> transactions(String userId) {
        return transactions(userId, null, null, null, null, 0);
    }

    public List<BankTransaction> transactions(String userId, String accountId, LocalDate from, LocalDate to,
            String category, int limit) {
        requireKnownUser(userId);
        int max = limit <= 0 ? 100 : Math.min(limit, 100);
        String accountFilter = accountId == null || accountId.isBlank() ? null : accountId.trim();
        String categoryFilter = category == null || category.isBlank() ? null : category.trim().toUpperCase(Locale.ROOT);
        return transactions.getOrDefault(userId, List.of()).stream()
                .filter(txn -> accountFilter == null || txn.accountId().equalsIgnoreCase(accountFilter))
                .filter(txn -> from == null || !txn.date().isBefore(from))
                .filter(txn -> to == null || !txn.date().isAfter(to))
                .filter(txn -> categoryFilter == null || txn.category().equalsIgnoreCase(categoryFilter))
                .sorted(Comparator.comparing(BankTransaction::date).reversed()
                        .thenComparing(BankTransaction::transactionId))
                .limit(max)
                .toList();
    }

    public List<BankTransaction> search(String userId, String keyword, int limit) {
        requireKnownUser(userId);
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        int max = limit <= 0 ? 10 : Math.min(limit, 50);
        return transactions(userId).stream()
                .filter(txn -> (txn.merchant() + " " + txn.description() + " " + txn.category() + " "
                        + txn.accountId()).toLowerCase(Locale.ROOT).contains(needle))
                .limit(max)
                .toList();
    }

    public SpendingSummary spendingSummary(String userId, YearMonth month, String accountId) {
        YearMonth target = month == null ? YearMonth.now() : month;
        String currency = currencyOf(userId, accountId);
        List<BankTransaction> txns = transactions(userId, accountId,
                target.atDay(1), target.atEndOfMonth(), null, 100).stream()
                .filter(BankTransaction::isSpending)
                .toList();
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (BankTransaction txn : txns) {
            total = total.add(txn.amount().abs());
            byCategory.merge(txn.category(), txn.amount().abs(), BigDecimal::add);
        }
        Map<String, BigDecimal> sorted = new LinkedHashMap<>();
        byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return new SpendingSummary(total.setScale(2, RoundingMode.HALF_UP), currency, txns.size(), sorted);
    }

    public IncomeSummary incomeSummary(String userId, YearMonth month, String accountId) {
        YearMonth target = month == null ? YearMonth.now() : month;
        String currency = currencyOf(userId, accountId);
        List<BankTransaction> txns = transactions(userId, accountId,
                target.atDay(1), target.atEndOfMonth(), null, 100).stream()
                .filter(BankTransaction::isIncome)
                .toList();
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> bySource = new LinkedHashMap<>();
        for (BankTransaction txn : txns) {
            total = total.add(txn.amount());
            bySource.merge(txn.merchant(), txn.amount(), BigDecimal::add);
        }
        Map<String, BigDecimal> sorted = new LinkedHashMap<>();
        bySource.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return new IncomeSummary(total.setScale(2, RoundingMode.HALF_UP), currency, txns.size(), sorted);
    }

    public List<BankCard> cards(String userId) {
        requireKnownUser(userId);
        return List.copyOf(cards.getOrDefault(userId, List.of()));
    }

    public String profile(String userId) {
        var user = users.resolve(userId);
        List<BankAccount> userAccounts = accounts(userId);
        Map<String, BigDecimal> balancePerCurrency = new LinkedHashMap<>();
        for (BankAccount account : userAccounts) {
            balancePerCurrency.merge(account.currency(), account.balance(), BigDecimal::add);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Customer: ").append(user.displayName()).append(" (userId=").append(user.id()).append(")\n");
        sb.append("Segment: ").append(user.segment()).append(" | home branch: ").append(user.homeBranch()).append('\n');
        sb.append("Accounts: ").append(userAccounts.size()).append('\n');
        balancePerCurrency.forEach((currency, balance) ->
                sb.append("- total balance in ").append(currency).append(": ")
                        .append(balance.setScale(2, RoundingMode.HALF_UP)).append('\n'));
        sb.append("Cards: ").append(cards(userId).size());
        return sb.toString().trim();
    }

    public static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank() || month.trim().equalsIgnoreCase("this month")) {
            return YearMonth.now();
        }
        return YearMonth.parse(month.trim());
    }

    public static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date.trim());
    }

    private void requireKnownUser(String userId) {
        users.resolve(userId);
    }

    private String currencyOf(String userId, String accountId) {
        if (accountId != null && !accountId.isBlank()) {
            return account(userId, accountId).map(BankAccount::currency)
                    .orElse(accounts(userId).get(0).currency());
        }
        return accounts(userId).get(0).currency();
    }

    private void seed(String userId) {
        Random rnd = new Random(userId.hashCode() * 31L + 7L);
        LocalDate today = LocalDate.now();

        String currency = userId.equals("bob") ? "USD" : "EUR";
        String checkingId = "ACC-" + userId.toUpperCase(Locale.ROOT) + "-CHECK";
        String secondId = "ACC-" + userId.toUpperCase(Locale.ROOT) + (userId.equals("carol") ? "-INVEST" : "-SAVINGS");
        String secondType = userId.equals("carol") ? "INVESTMENT" : "SAVINGS";
        String secondName = userId.equals("carol") ? "Investment account" : "Savings account";

        List<BankTransaction> txns = new ArrayList<>();
        int[] seq = { 1 };

        BigDecimal salary = switch (userId) {
            case "carol" -> bd(6800 + rnd.nextInt(400));
            case "bob" -> bd(5200 + rnd.nextInt(300));
            default -> bd(4100 + rnd.nextInt(300));
        };
        String employer = userId.equals("carol") ? "Client invoices" : "Employer GmbH";

        // Income: salary/invoices on the 1st of the current and previous two months.
        for (int m = 0; m < 3; m++) {
            LocalDate day = today.withDayOfMonth(1).minusMonths(m);
            txns.add(txn(seq, checkingId, day, salary, currency, employer, "INCOME",
                    userId.equals("carol") ? "Monthly client invoice" : "Monthly salary"));
        }
        if (userId.equals("carol")) {
            txns.add(txn(seq, checkingId, today.minusDays(9), bd(900 + rnd.nextInt(500)), currency,
                    "Side project payout", "INCOME", "Freelance payout"));
        }

        // Spending: a few transactions in the current month plus random ones over 90 days.
        for (int i = 0; i < 4; i++) {
            int back = 1 + rnd.nextInt(Math.max(1, today.getDayOfMonth() - 1));
            addSpending(txns, seq, rnd, checkingId, today.minusDays(back), currency);
        }
        for (int i = 0; i < 55; i++) {
            addSpending(txns, seq, rnd, rnd.nextBoolean() ? checkingId : secondId, today.minusDays(rnd.nextInt(90)), currency);
        }
        // A savings transfer each month.
        for (int m = 0; m < 3; m++) {
            txns.add(txn(seq, secondId, today.withDayOfMonth(1).minusMonths(m).plusDays(2),
                    bd(-200), currency, "Internal transfer", "TRANSFER", "Monthly savings deposit"));
        }

        txns.sort(Comparator.comparing(BankTransaction::date).reversed()
                .thenComparing(BankTransaction::transactionId).reversed());

        BigDecimal checkingBalance = bd(3500 + rnd.nextInt(1500));
        BigDecimal secondBalance = bd(12000 + rnd.nextInt(8000));
        for (BankTransaction txn : txns) {
            if (txn.accountId().equals(checkingId)) {
                checkingBalance = checkingBalance.add(txn.amount());
            }
            else {
                secondBalance = secondBalance.add(txn.amount());
            }
        }

        List<BankAccount> userAccounts = List.of(
                new BankAccount(checkingId, "Everyday Checking", "CHECKING", currency,
                        checkingBalance.setScale(2, RoundingMode.HALF_UP), fakeIban(userId, 1)),
                new BankAccount(secondId, secondName, secondType, currency,
                        secondBalance.setScale(2, RoundingMode.HALF_UP), fakeIban(userId, 2)));

        BigDecimal spentThisMonth = spendingSummary(userId, YearMonth.now(), checkingId).total();
        List<BankCard> userCards = List.of(
                new BankCard("CARD-" + userId.toUpperCase(Locale.ROOT) + "-1", "**** " + (1000 + rnd.nextInt(9000)),
                        "DEBIT", "ACTIVE", bd(3000), spentThisMonth),
                new BankCard("CARD-" + userId.toUpperCase(Locale.ROOT) + "-2", "**** " + (1000 + rnd.nextInt(9000)),
                        "CREDIT", "ACTIVE", bd(5000), bd(0)));

        accounts.put(userId, userAccounts);
        transactions.put(userId, List.copyOf(txns));
        cards.put(userId, userCards);
    }

    private void addSpending(List<BankTransaction> txns, int[] seq, Random rnd, String accountId,
            LocalDate date, String currency) {
        String category = SPEND_CATEGORIES[rnd.nextInt(SPEND_CATEGORIES.length)];
        String[] merchants = MERCHANTS.get(category);
        String merchant = merchants[rnd.nextInt(merchants.length)];
        BigDecimal amount = bd(-(8 + rnd.nextInt(180)) - rnd.nextInt(100) / 100.0);
        txns.add(txn(seq, accountId, date, amount, currency, merchant, category,
                category.toLowerCase(Locale.ROOT) + " at " + merchant));
    }

    private BankTransaction txn(int[] seq, String accountId, LocalDate date, BigDecimal amount,
            String currency, String merchant, String category, String description) {
        return new BankTransaction("TXN-" + seq[0]++, accountId, date, amount.setScale(2, RoundingMode.HALF_UP),
                currency, merchant, category, description);
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String fakeIban(String userId, int n) {
        return "DE" + (10 + n) + "370004" + String.format("%010d", Math.abs((userId + n).hashCode()) % 10_000_000_000L);
    }
}
