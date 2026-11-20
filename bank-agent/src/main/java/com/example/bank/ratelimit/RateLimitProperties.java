package com.example.bank.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Simple per-user request budgets for mutating endpoints (config-gated). */
@Component
public class RateLimitProperties {

    private final boolean enabled;
    private final int jobsPerMinute;
    private final int transfersPerMinute;
    private final int registrationsPerMinute;

    public RateLimitProperties(
            @Value("${bank.rate-limit.enabled:true}") boolean enabled,
            @Value("${bank.rate-limit.jobs-per-minute:20}") int jobsPerMinute,
            @Value("${bank.rate-limit.transfers-per-minute:30}") int transfersPerMinute,
            @Value("${bank.rate-limit.registrations-per-minute:20}") int registrationsPerMinute) {
        this.enabled = enabled;
        this.jobsPerMinute = jobsPerMinute < 1 ? 20 : jobsPerMinute;
        this.transfersPerMinute = transfersPerMinute < 1 ? 30 : transfersPerMinute;
        this.registrationsPerMinute = registrationsPerMinute < 1 ? 20 : registrationsPerMinute;
    }

    public boolean enabled() {
        return enabled;
    }

    public int jobsPerMinute() {
        return jobsPerMinute;
    }

    public int transfersPerMinute() {
        return transfersPerMinute;
    }

    public int registrationsPerMinute() {
        return registrationsPerMinute;
    }
}
