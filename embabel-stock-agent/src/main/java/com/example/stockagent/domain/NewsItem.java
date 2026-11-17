package com.example.stockagent.domain;

public record NewsItem(
        String headline,
        String source,
        String url,
        long publishedAtEpochSeconds,
        String summary
) {
}
