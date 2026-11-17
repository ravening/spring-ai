package com.example.stockagent.domain;

/** Result of the price research agent. */
public record PriceResearch(
        PriceQuote quote,
        PriceHistory history,
        String note
) {
}
