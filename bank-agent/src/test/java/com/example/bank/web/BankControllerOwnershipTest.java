package com.example.bank.web;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import com.example.bank.bank.BankStore;
import com.example.bank.bank.TransferService;
import com.example.bank.data.BankData;
import com.example.bank.jobs.JobProperties;
import com.example.bank.notify.NotificationService;
import com.example.bank.ratelimit.RateLimitProperties;
import com.example.bank.ratelimit.RateLimiter;
import com.example.bank.tools.ToolCatalog;
import com.example.bank.users.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc ownership + rate-limit checks for accounts and transfers. */
class BankControllerOwnershipTest {

    private BankStore bankStore;
    private TransferService transfers;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:bc_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        UserDirectory users = new UserDirectory();
        bankStore = new BankStore(jdbc, new BankData(users), users);
        bankStore.createTables();
        JobProperties props = new JobProperties(true, Duration.ofSeconds(5), 4, 50,
                BigDecimal.valueOf(100), "UTC", 100, 60);
        transfers = new TransferService(bankStore, mock(NotificationService.class), users, props);
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 20, 2, 20));
        mvc = MockMvcBuilders.standaloneSetup(new BankController(users, mock(ToolCatalog.class),
                        bankStore, transfers, limiter, new RateLimitProperties(true, 20, 2, 20)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void accountsAreScopedToTheRequestingUser() throws Exception {
        mvc.perform(get("/api/accounts").param("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value("alice"));
        mvc.perform(get("/api/accounts").param("userId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value("bob"));
        mvc.perform(get("/api/accounts").param("userId", "nobody")).andExpect(status().isBadRequest());
    }

    @Test
    void transferAuditIsScopedToSenderAndRecipient() throws Exception {
        String from = bankStore.accounts("alice").stream()
                .filter(a -> a.accountId().endsWith("-CHECK")).findFirst().orElseThrow().accountId();
        String to = bankStore.accounts("carol").stream()
                .filter(a -> a.accountId().endsWith("-CHECK")).findFirst().orElseThrow().accountId();
        transfers.transfer("alice", from, "carol", to, "10", "EUR", "audit", "audit-1");

        mvc.perform(get("/api/transfers").param("userId", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/transfers").param("userId", "carol"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/transfers").param("userId", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cannotTransferFromSomeoneElsesAccountOverCapOrWithBadInput() throws Exception {
        String aliceAccount = bankStore.accounts("alice").stream()
                .filter(a -> a.accountId().endsWith("-CHECK")).findFirst().orElseThrow().accountId();
        String bobAccount = bankStore.accounts("bob").stream()
                .filter(a -> a.accountId().endsWith("-CHECK")).findFirst().orElseThrow().accountId();

        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"userId\":\"bob\",\"fromAccountId\":\"" + aliceAccount + "\",\"toUserId\":\"bob\","
                                + "\"toAccountId\":\"" + bobAccount + "\",\"amount\":\"1\",\"currency\":\"EUR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"userId\":\"bob\",\"fromAccountId\":\"" + bobAccount + "\",\"toUserId\":\"bob\","
                                + "\"toAccountId\":\"" + bobAccount + "\",\"amount\":\"500\",\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transferCreationIsRateLimitedPerUser() throws Exception {
        String bobAccount = bankStore.accounts("bob").stream()
                .filter(a -> a.accountId().endsWith("-CHECK")).findFirst().orElseThrow().accountId();
        String body = "{\"userId\":\"bob\",\"fromAccountId\":\"" + bobAccount + "\",\"toUserId\":\"bob\","
                + "\"toAccountId\":\"" + bobAccount + "\",\"amount\":\"1\",\"currency\":\"USD\"}";
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }
}
