package com.example.bank.jobs;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.example.bank.bank.BankStore;
import com.example.bank.bank.TransferService;
import com.example.bank.chat.ConversationService;
import com.example.bank.data.BankData;
import com.example.bank.jobs.JobDtos.CreateJobRequest;
import com.example.bank.jobs.JobDtos.JobDto;
import com.example.bank.notify.NotificationService;
import com.example.bank.ratelimit.RateLimitProperties;
import com.example.bank.ratelimit.RateLimiter;
import com.example.bank.users.UserDirectory;
import com.example.bank.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc ownership + rate-limit checks for the job REST surface. */
class JobControllerOwnershipTest {

    private JobService jobs;
    private MockMvc mvc;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:jc_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        UserDirectory users = new UserDirectory();
        ChatMemoryRepository memoryRepository = mock(ChatMemoryRepository.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JobService> provider = mock(ObjectProvider.class);
        final JobService[] holder = new JobService[1];
        when(provider.getIfAvailable()).thenAnswer(inv -> holder[0]);
        ConversationService conversations = new ConversationService(jdbc, memoryRepository, chatMemory, provider);
        conversations.createTable();
        conversations.create("alice", "conv-alice");
        conversations.create("bob", "conv-bob");

        NotificationService notifications = mock(NotificationService.class);
        JobProperties props = new JobProperties(true, Duration.ofSeconds(5), 4, 50,
                BigDecimal.valueOf(100), "UTC", 100, 60);
        BankStore bankStore = new BankStore(jdbc, new BankData(users), users);
        bankStore.createTables();
        TransferService transfers = new TransferService(bankStore, notifications, users, props);
        jobs = new JobService(jdbc, props, users, conversations, notifications,
                List.of(new ReminderJobExecutor(), new TransferJobExecutor(transfers, users)));
        jobs.createTables();
        holder[0] = jobs;

        limiter = new RateLimiter(new RateLimitProperties(true, 2, 3, 4));
        mvc = MockMvcBuilders.standaloneSetup(new JobController(jobs, users, limiter,
                        new RateLimitProperties(true, 2, 3, 4)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private String aliceJob() {
        JobDto job = jobs.createForUser("alice", new CreateJobRequest("alice", "conv-alice", "reminder",
                "{\"text\":\"hello\"}", null, 5, null, null, "UTC"));
        return job.id();
    }

    @Test
    void bobCannotListReadEditCancelOrRunAlicesJob() throws Exception {
        String id = aliceJob();
        mvc.perform(get("/api/jobs").param("userId", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/jobs").param("userId", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/jobs/{id}", id).param("userId", "bob")).andExpect(status().isForbidden());
        mvc.perform(get("/api/jobs/{id}", id).param("userId", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job.id").value(id));

        mvc.perform(patch("/api/jobs/{id}", id).param("userId", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/jobs/{id}", id).param("userId", "bob")).andExpect(status().isForbidden());
        mvc.perform(post("/api/jobs/{id}/run", id).param("userId", "bob")).andExpect(status().isForbidden());
    }

    @Test
    void aliceCanEditCancelAndRunHerOwnJob() throws Exception {
        String id = aliceJob();
        mvc.perform(patch("/api/jobs/{id}", id).param("userId", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
        mvc.perform(post("/api/jobs/{id}/run", id).param("userId", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runCount").value(1));
        mvc.perform(delete("/api/jobs/{id}", id).param("userId", "alice"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/jobs/{id}", id).param("userId", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job.status").value("CANCELLED"));
    }

    @Test
    void creatingJobInSomeoneElsesConversationIsForbidden() throws Exception {
        mvc.perform(post("/api/jobs").param("userId", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\",\"conversationId\":\"conv-bob\",\"type\":\"reminder\","
                                + "\"payload\":\"{\\\"text\\\":\\\"hi\\\"}\",\"delayMinutes\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void jobCreationIsRateLimitedPerUser() throws Exception {
        String body = "{\"userId\":\"bob\",\"conversationId\":\"conv-bob\",\"type\":\"reminder\","
                + "\"payload\":\"{\\\"text\\\":\\\"hi\\\"}\",\"delayMinutes\":5}";
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
        // Another user has an independent budget.
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(
                        body.replace("\"bob\"", "\"alice\"").replace("conv-bob", "conv-alice")))
                .andExpect(status().isCreated());
    }
}
