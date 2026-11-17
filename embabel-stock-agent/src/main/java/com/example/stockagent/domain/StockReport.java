package com.example.stockagent.domain;

/** Final typed result of the stock analysis goal. */
public record StockReport(
        String symbol,
        PriceQuote quote,
        TechnicalResearch technicals,
        NewsResearch news,
        FundamentalResearch fundamentals,
        Verdict verdict,
        String markdown,
        long generatedAtEpochSeconds
) {
}
