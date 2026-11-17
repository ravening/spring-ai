package com.example.stockagent.domain;

import java.util.List;

/** Combined result of all parallel research branches. */
public record ResearchBundle(
        String symbol,
        PriceResearch price,
        NewsResearch news,
        FundamentalResearch fundamentals,
        TechnicalResearch technicals,
        List<String> errors
) {
}
