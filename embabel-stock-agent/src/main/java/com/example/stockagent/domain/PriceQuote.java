package com.example.stockagent.domain;

/** Real-time quote from Finnhub. */
public record PriceQuote(
        String symbol,
        double currentPrice,
        double change,
        double changePercent,
        double dayHigh,
        double dayLow,
        double open,
        double previousClose,
        long asOfEpochSeconds,
        String source
) {
}
