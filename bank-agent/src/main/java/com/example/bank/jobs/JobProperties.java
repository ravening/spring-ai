package com.example.bank.jobs;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuration for the durable scheduler and the transfer amount cap. */
@Component
public class JobProperties {

    private final boolean enabled;
    private final Duration pollInterval;
    private final int workerThreads;
    private final int maxActivePerUser;
    private final BigDecimal maxTransferAmount;
    private final String defaultTimezone;
    private final int maxCatchup;
    private final int leaseSeconds;

    public JobProperties(
            @Value("${bank.jobs.enabled:true}") boolean enabled,
            @Value("${bank.jobs.poll-interval:5s}") Duration pollInterval,
            @Value("${bank.jobs.worker-threads:4}") int workerThreads,
            @Value("${bank.jobs.max-active-per-user:50}") int maxActivePerUser,
            @Value("${bank.jobs.max-transfer-amount:100}") BigDecimal maxTransferAmount,
            @Value("${bank.jobs.default-timezone:UTC}") String defaultTimezone,
            @Value("${bank.jobs.max-catchup:100}") int maxCatchup,
            @Value("${bank.jobs.lease-seconds:60}") int leaseSeconds) {
        this.enabled = enabled;
        this.pollInterval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                ? Duration.ofSeconds(5) : pollInterval;
        this.workerThreads = workerThreads < 1 ? 4 : workerThreads;
        this.maxActivePerUser = maxActivePerUser < 1 ? 50 : maxActivePerUser;
        this.maxTransferAmount = maxTransferAmount == null || maxTransferAmount.signum() <= 0
                ? BigDecimal.valueOf(100) : maxTransferAmount;
        this.defaultTimezone = defaultTimezone == null || defaultTimezone.isBlank() ? "UTC" : defaultTimezone;
        this.maxCatchup = maxCatchup < 1 ? 100 : maxCatchup;
        this.leaseSeconds = leaseSeconds < 1 ? 60 : leaseSeconds;
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int maxActivePerUser() {
        return maxActivePerUser;
    }

    public BigDecimal maxTransferAmount() {
        return maxTransferAmount;
    }

    public String defaultTimezone() {
        return defaultTimezone;
    }

    public int maxCatchup() {
        return maxCatchup;
    }

    public int leaseSeconds() {
        return leaseSeconds;
    }
}
