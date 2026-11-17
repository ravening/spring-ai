package com.example.stockagent.domain;

public record Candle(
        long epochSeconds,
        double open,
        double high,
        double low,
        double close,
        double volume
) {
}
