package com.example.bank.data;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.example.bank.users.UserDirectory;
import org.junit.jupiter.api.Test;

import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankDataTest {

    private final BankData data = new BankData(new UserDirectory());

    @Test
    void seedsAccountsForEveryDemoUser() {
        for (String userId : List.of("alice", "bob", "carol")) {
            List<BankAccount> accounts = data.accounts(userId);
            assertEquals(2, accounts.size(), "expected checking + second account for " + userId);
            assertTrue(accounts.stream().allMatch(a -> a.balance() != null));
            assertTrue(accounts.stream().allMatch(a -> a.iban() != null && a.iban().startsWith("DE")));
        }
        assertEquals("USD", data.accounts("bob").get(0).currency());
        assertEquals("EUR", data.accounts("alice").get(0).currency());
    }

    @Test
    void rejectsUnknownUsers() {
        assertThrows(ResponseStatusException.class, () -> data.accounts("mallory"));
    }

    @Test
    void transactionsAreSortedNewestFirstAndRespectLimit() {
        List<BankTransaction> txns = data.transactions("alice", null, null, null, null, 10);
        assertEquals(10, txns.size());
        for (int i = 1; i < txns.size(); i++) {
            assertFalse(txns.get(i - 1).date().isBefore(txns.get(i).date()));
        }
    }

    @Test
    void currentMonthHasIncomeAndSpending() {
        YearMonth now = YearMonth.now();
        IncomeSummary income = data.incomeSummary("alice", now, null);
        assertTrue(income.total().compareTo(BigDecimal.ZERO) > 0, "salary should be booked this month");
        assertTrue(income.bySource().containsKey("Employer GmbH"));

        SpendingSummary spending = data.spendingSummary("alice", now, null);
        assertTrue(spending.total().compareTo(BigDecimal.ZERO) > 0);
        BigDecimal categorySum = spending.byCategory().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, spending.total().compareTo(categorySum), "category breakdown must add up to the total");
        assertEquals(spending.transactionCount(), data.transactions("alice", null,
                now.atDay(1), now.atEndOfMonth(), null, 100).stream()
                .filter(BankTransaction::isSpending).count());
    }

    @Test
    void searchFindsSalaryAndFiltersByCategory() {
        List<BankTransaction> salary = data.search("alice", "salary", 10);
        assertFalse(salary.isEmpty());
        assertTrue(salary.stream().allMatch(t -> t.category().equals("INCOME")));

        List<BankTransaction> groceries = data.transactions("alice", null, null, null, "GROCERIES", 100);
        assertTrue(groceries.stream().allMatch(t -> t.category().equals("GROCERIES")));
    }

    @Test
    void dataIsDeterministicAcrossInstances() {
        BankData other = new BankData(new UserDirectory());
        assertEquals(data.transactions("carol"), other.transactions("carol"));
        assertEquals(data.accounts("bob"), other.accounts("bob"));
    }

    @Test
    void profileMentionsUserAndBalances() {
        String profile = data.profile("carol");
        assertTrue(profile.contains("Carol Clark"));
        assertTrue(profile.contains("userId=carol"));
        assertTrue(profile.contains("Accounts: 2"));
    }
}
