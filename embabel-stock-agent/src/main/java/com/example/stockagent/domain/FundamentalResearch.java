package com.example.stockagent.domain;

/** Company facts and upcoming events from Finnhub. */
public record FundamentalResearch(
        String symbol,
        String companyName,
        String industry,
        String nextEarningsDate,
        String earningsHour,
        String earningsQuarter,
        String earningsPerShareEstimate,
        String dividendPerShare,
        String dividendYield,
        String exDividendDate,
        String marketCap,
        String description,
        long asOfEpochSeconds
) {
}
