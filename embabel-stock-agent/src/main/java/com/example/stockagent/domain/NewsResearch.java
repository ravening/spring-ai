package com.example.stockagent.domain;

import java.util.List;

/** Result of the news research agent. */
public record NewsResearch(
        String symbol,
        List<NewsItem> items,
        String sentiment,
        String summary,
        long asOfEpochSeconds
) {
}
