package com.example.bank.core;

import java.time.Instant;

public record AgentEvent(
        Instant at,
        String type,
        String agent,
        String message,
        String detail,
        Long durationMs) {
}
