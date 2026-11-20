package com.example.bank.jobs;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.bank.bank.BankStore;
import com.example.bank.bank.TransferService;
import com.example.bank.chat.ConversationService;
import com.example.bank.data.BankData;
import com.example.bank.jobs.JobDtos.ClaimedJob;
import com.example.bank.jobs.JobDtos.CreateJobRequest;
import com.example.bank.jobs.JobDtos.JobDto;
import com.example.bank.notify.NotificationDtos.NotificationEvent;
import com.example.bank.notify.NotificationService;
import com.example.bank.users.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobServiceTest {

    private JobService jobs;
    private ConversationService conversations;
    private NotificationService notifications;
    private UserDirectory users;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:j_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        users = new UserDirectory();
        ChatMemoryRepository memoryRepository = mock(ChatMemoryRepository.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JobService> jobProvider = mock(ObjectProvider.class);
        final JobService[] holder = new JobService[1];
        when(jobProvider.getIfAvailable()).thenAnswer(inv -> holder[0]);
        conversations = new ConversationService(jdbc, memoryRepository, chatMemory, jobProvider);
        conversations.createTable();
        conversations.create("alice", "conv-alice");
        conversations.create("bob", "conv-bob");

        notifications = mock(NotificationService.class);
        JobProperties props = new JobProperties(true, Duration.ofSeconds(5), 4, 50,
                BigDecimal.valueOf(100), "UTC", 100, 60);
        BankStore bankStore = new BankStore(jdbc, new BankData(users), users);
        bankStore.createTables();
        TransferService transfers = new TransferService(bankStore, notifications, users, props);
        List<JobExecutor> executors = List.of(
                new ReminderJobExecutor(), new TransferJobExecutor(transfers, users));
        jobs = new JobService(jdbc, props, users, conversations, notifications, executors);
        jobs.createTables();
        holder[0] = jobs;
    }

    private JobDto reminder(String owner, String conversationId, String scheduleField, Object value) {
        CreateJobRequest request = new CreateJobRequest(owner, conversationId, "reminder",
                "{\"text\":\"wake up\"}", null, null, null, null, "UTC");
        switch (scheduleField) {
            case "delay" -> request = new CreateJobRequest(owner, conversationId, "reminder", request.payload(),
                    null, (Integer) value, null, null, "UTC");
            case "cron" -> request = new CreateJobRequest(owner, conversationId, "reminder", request.payload(),
                    null, null, (String) value, null, "UTC");
            case "interval" -> request = new CreateJobRequest(owner, conversationId, "reminder", request.payload(),
                    null, null, null, (Integer) value, "UTC");
            case "runAt" -> request = new CreateJobRequest(owner, conversationId, "reminder", request.payload(),
                    (String) value, null, null, null, "UTC");
            default -> throw new IllegalArgumentException(scheduleField);
        }
        return jobs.createForUser(owner, request);
    }

    @Test
    void ownershipAndValidationAreEnforced() {
        assertThrows(ResponseStatusException.class, () -> reminder("alice", "conv-bob", "delay", 1));
        assertThrows(ResponseStatusException.class, () -> reminder("alice", "conv-alice", "cron", "bad cron"));
        assertThrows(ResponseStatusException.class, () -> reminder("alice", "conv-alice", "runAt",
                Instant.now().minusSeconds(60).toString()));
        assertThrows(ResponseStatusException.class, () -> jobs.getForUser("bob",
                reminder("alice", "conv-alice", "delay", 1).id()));
    }

    @Test
    void cronAndIntervalComputeFutureNextRuns() {
        long now = System.currentTimeMillis();
        assertTrue(reminder("alice", "conv-alice", "cron", "0 9 * * 5 *").runAt() > now);
        assertTrue(reminder("alice", "conv-alice", "interval", 30).runAt() > now + 29 * 60_000L);
    }

    @Test
    void claimIsAtomicAndExecutionWritesExactlyOneRunAndNotification() {
        JobDto job = reminder("alice", "conv-alice", "delay", 0);
        long now = System.currentTimeMillis();

        List<ClaimedJob> claimed = jobs.claimDue(now, 10);
        assertEquals(1, claimed.size());
        assertTrue(jobs.claimDue(now, 10).isEmpty(), "same row cannot be claimed twice");

        jobs.execute(claimed.get(0));
        assertEquals("DONE", jobs.getForUser("alice", job.id()).job().status());
        Long runs = jdbc.queryForObject("SELECT COUNT(*) FROM job_runs WHERE job_id = ?", Long.class, job.id());
        assertEquals(1L, runs);
        String status = jdbc.queryForObject("SELECT status FROM job_runs WHERE job_id = ?", String.class, job.id());
        assertEquals("SUCCESS", status);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notifications, times(1)).emit(captor.capture());
        NotificationEvent event = captor.getValue();
        assertEquals("job_result", event.type());
        assertEquals(job.id(), event.jobId());
        assertNotNull(event.chatMessage());

        // Replaying the same claimed occurrence must not emit a second notification.
        jobs.execute(claimed.get(0));
        verify(notifications, times(1)).emit(any());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM job_runs WHERE job_id = ?", Long.class, job.id()));
    }

    @Test
    void bootRecoveryResetsStaleLeasesAndDoesNotBackfillRecurringJobs() {
        JobDto stuck = reminder("alice", "conv-alice", "delay", 0);
        jdbc.update("UPDATE scheduled_jobs SET status = 'RUNNING', lease_until = ? WHERE id = ?",
                System.currentTimeMillis() - 10_000, stuck.id());
        long past = System.currentTimeMillis() - 60_000;
        JobDto recurring = reminder("alice", "conv-alice", "cron", "0 9 * * 5 *");
        jdbc.update("UPDATE scheduled_jobs SET run_at = ? WHERE id = ?", past, recurring.id());

        jobs.recoverOnBoot();

        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM scheduled_jobs WHERE id = ?",
                String.class, stuck.id()));
        Long recoveredRunAt = jdbc.queryForObject("SELECT run_at FROM scheduled_jobs WHERE id = ?",
                Long.class, recurring.id());
        assertTrue(recoveredRunAt > System.currentTimeMillis(), "recurring jobs move to next future run");
    }

    @Test
    void deletingAConversationCancelsItsJobs() {
        JobDto job = reminder("alice", "conv-alice", "delay", 5);
        conversations.delete("conv-alice");
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM scheduled_jobs WHERE id = ?",
                String.class, job.id()));
    }
}
