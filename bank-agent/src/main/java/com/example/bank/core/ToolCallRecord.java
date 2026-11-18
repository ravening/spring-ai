package com.example.bank.core;

import java.time.Instant;

public record ToolCallRecord(
        Instant at,
        String agent,
        String origin,
        String name,
        String arguments,
        String result,
        long durationMs,
        boolean error) {
}
