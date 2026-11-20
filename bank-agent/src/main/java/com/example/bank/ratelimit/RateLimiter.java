package com.example.bank.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sliding-window, per-user + per-action rate limiter for user-initiated writes
 * (job creation, transfers, device registration). Server-internal job executions
 * are bounded separately by the per-user job cap and the worker pool, so they do
 * not consume this budget. Single-instance in-memory implementation, like the
 * rest of the PoC scheduler; a distributed deployment would replace this with a
 * shared store.
 */
@Component
public class RateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final RateLimitProperties properties;
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    /** Throws 429 when the user has exceeded {@code limit} actions in the last minute. */
    public void check(String action, String userId, int limit) {
        if (!properties.enabled() || limit <= 0 || userId == null) {
            return;
        }
        String key = action + ":" + userId;
        long now = System.currentTimeMillis();
        Deque<Long> hits = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() >= WINDOW_MS) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many " + action + " requests. Please try again shortly.");
            }
            hits.addLast(now);
        }
        // Opportunistic cleanup so the key set cannot grow without bound.
        if (windows.size() > 1000) {
            windows.entrySet().removeIf(entry -> {
                Deque<Long> times = entry.getValue();
                synchronized (times) {
                    while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) {
                        times.pollFirst();
                    }
                    return times.isEmpty();
                }
            });
        }
    }

    /** Test helper: forget all recorded hits. */
    public void clear() {
        windows.clear();
    }
}
