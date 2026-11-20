package com.example.bank.jobs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.bank.jobs.JobDtos.ClaimedJob;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Single-instance scheduler: a fixed-delay poller atomically claims due jobs and
 * hands them to a bounded worker pool. A lease + boot sweep recovers jobs left
 * RUNNING by a crashed worker. No Quartz/broker/distributed coordination.
 */
@Configuration
@EnableScheduling
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobService jobs;
    private final JobProperties properties;
    private final ExecutorService workers;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public JobScheduler(JobService jobs, JobProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
        this.workers = Executors.newFixedThreadPool(properties.workerThreads(), runnable -> {
            Thread thread = new Thread(runnable, "bank-job-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void boot() {
        if (!properties.enabled()) {
            return;
        }
        jobs.recoverOnBoot();
        // Overdue one-shot jobs are caught up at boot, capped globally by max-catchup.
        // Recurring jobs were already moved to their next future run by recoverOnBoot.
        long now = System.currentTimeMillis();
        for (ClaimedJob claimed : jobs.claimDue(now, properties.maxCatchup())) {
            workers.submit(() -> safeExecute(claimed));
        }
    }

    @Scheduled(fixedDelayString = "${bank.jobs.poll-interval:5s}")
    void poll() {
        if (!properties.enabled() || !ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            int limit = Math.max(properties.workerThreads() * 2, properties.maxCatchup());
            for (ClaimedJob claimed : jobs.claimDue(now, limit)) {
                workers.submit(() -> safeExecute(claimed));
            }
        } finally {
            ticking.set(false);
        }
    }

    private void safeExecute(ClaimedJob claimed) {
        try {
            jobs.execute(claimed);
        } catch (Exception ex) {
            log.error("Unhandled failure executing job {}", claimed.job().id(), ex);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
