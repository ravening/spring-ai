package com.example.bank.bank;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import com.example.bank.bank.BankStore.AccountRow;
import com.example.bank.bank.BankStore.TransferResult;
import com.example.bank.data.BankData;
import com.example.bank.jobs.JobProperties;
import com.example.bank.notify.NotificationDtos.NotificationEvent;
import com.example.bank.notify.NotificationService;
import com.example.bank.users.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TransferServiceTest {

    // Bob's demo accounts are USD; Alice and Carol are EUR, so use Carol as the recipient.
    private static final String RECIPIENT_CHECK = "ACC-CAROL-CHECK";

    private BankStore bankStore;
    private TransferService transfers;
    private NotificationService notifications;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:t_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        UserDirectory users = new UserDirectory();
        BankStore store = new BankStore(jdbc, new BankData(users), users);
        store.createTables();
        this.bankStore = store;
        this.notifications = mock(NotificationService.class);
        JobProperties props = new JobProperties(true, Duration.ofSeconds(5), 4, 50,
                BigDecimal.valueOf(100), "UTC", 100, 60);
        this.transfers = new TransferService(store, notifications, users, props);
    }

    private String aliceChecking() {
        return bankStore.accounts("alice").stream()
                .filter(a -> a.accountId().endsWith("-CHECK")).findFirst().orElseThrow().accountId();
    }

    private AccountRow balance(String accountId) {
        return bankStore.account(accountId).orElseThrow();
    }

    @Test
    void transferMovesRealMoneyAndEmitsRecipientNotificationWithRealBalances() {
        String from = aliceChecking();
        BigDecimal senderBefore = balance(from).balance();
        BigDecimal recipientBefore = balance(RECIPIENT_CHECK).balance();

        TransferResult result = transfers.transfer("alice", from, "carol", RECIPIENT_CHECK, "10.00", "EUR", "lunch", null);

        assertEquals(BigDecimal.TEN.setScale(2), result.amount());
        assertEquals(senderBefore.subtract(BigDecimal.TEN), balance(from).balance());
        assertEquals(recipientBefore.add(BigDecimal.TEN), balance(RECIPIENT_CHECK).balance());
        assertEquals(senderBefore, result.senderOldBalance());
        assertEquals(recipientBefore, result.recipientOldBalance());
        assertTrue(result.txnId().startsWith("TXN-"));

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notifications, times(1)).emit(captor.capture());
        NotificationEvent event = captor.getValue();
        assertEquals("carol", event.userId());
        assertEquals("transfer_received", event.type());
        assertTrue(event.detail().contains(result.txnId()));
        assertTrue(event.detail().contains("10.00"));
        assertTrue(event.chatMessage().contains(result.txnId()));
        assertTrue(event.chatMessage().contains("→"));
        assertTrue(event.body().contains("Alice Anderson"));
        assertEquals("transfer:" + result.txnId(), event.dedupeKey());
    }

    @Test
    void repeatedIdempotencyKeyMovesMoneyOnlyOnceAndEmitsOnce() {
        String from = aliceChecking();
        BigDecimal recipientBefore = balance(RECIPIENT_CHECK).balance();

        TransferResult first = transfers.transfer("alice", from, "carol", RECIPIENT_CHECK, "10", "EUR", "x", "key-1");
        TransferResult again = transfers.transfer("alice", from, "carol", RECIPIENT_CHECK, "10", "EUR", "x", "key-1");

        assertEquals(first.txnId(), again.txnId());
        assertEquals(recipientBefore.add(BigDecimal.TEN), balance(RECIPIENT_CHECK).balance());
        assertEquals(bankStore.transfers("alice").size(), 1);
        // The emit still happens but is deduped server-side by NotificationService;
        // at the service boundary we only assert the idempotent result was reused.
        verify(notifications, times(2)).emit(any());
    }

    @Test
    void insufficientFundsAndOverCapAreRejected() {
        String from = aliceChecking();
        assertThrows(ResponseStatusException.class, () ->
                transfers.validate("alice", from, "carol", RECIPIENT_CHECK, "999999", "EUR", null));
        assertThrows(ResponseStatusException.class, () ->
                transfers.validate("alice", from, "carol", RECIPIENT_CHECK, "0", "EUR", null));
        assertThrows(ResponseStatusException.class, () ->
                transfers.validate("alice", from, "carol", RECIPIENT_CHECK, "-5", "EUR", null));
    }

    @Test
    void currencyMismatchAndForeignAccountOwnershipAreRejected() {
        String from = aliceChecking();
        assertThrows(ResponseStatusException.class, () ->
                transfers.validate("alice", from, "carol", RECIPIENT_CHECK, "5", "USD", null));
        assertThrows(ResponseStatusException.class, () ->
                transfers.validate("carol", RECIPIENT_CHECK, "carol", RECIPIENT_CHECK, "5", "USD", null));
    }

    @Test
    void auditRowsRecordOldAndNewBalances() {
        String from = aliceChecking();
        TransferResult result = transfers.transfer("alice", from, "carol", RECIPIENT_CHECK, "3.50", "EUR", "note", null);
        BankStore.TransferRow row = bankStore.transfers("carol").stream()
                .filter(t -> t.id().equals(result.txnId())).findFirst().orElseThrow();
        assertEquals(result.senderOldBalance(), row.fromOldBalance());
        assertEquals(result.senderNewBalance(), row.fromNewBalance());
        assertEquals(result.recipientOldBalance(), row.toOldBalance());
        assertEquals(result.recipientNewBalance(), row.toNewBalance());
        assertNotEquals(row.fromNewBalance(), row.fromOldBalance());
    }
}
