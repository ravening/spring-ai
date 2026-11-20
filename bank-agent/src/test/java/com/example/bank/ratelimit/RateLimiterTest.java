package com.example.bank.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimiterTest {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter(new RateLimitProperties(true, 2, 3, 4));
    }

    @Test
    void blocksAfterThePerUserPerActionBudget() {
        assertDoesNotThrow(() -> limiter.check("jobs", "alice", 2));
        assertDoesNotThrow(() -> limiter.check("jobs", "alice", 2));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> limiter.check("jobs", "alice", 2));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void budgetsAreIndependentPerUserAndPerAction() {
        limiter.check("jobs", "alice", 1);
        assertThrows(ResponseStatusException.class, () -> limiter.check("jobs", "alice", 1));
        assertDoesNotThrow(() -> limiter.check("jobs", "bob", 1));
        assertDoesNotThrow(() -> limiter.check("transfers", "alice", 1));
    }

    @Test
    void disabledLimiterNeverBlocks() {
        RateLimiter disabled = new RateLimiter(new RateLimitProperties(false, 1, 1, 1));
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> disabled.check("jobs", "alice", 1));
        }
    }
}
