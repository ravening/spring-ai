package com.example.stockagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Finnhub finnhub,
        Yahoo yahoo,
        Chat chat,
        Rag rag,
        Uploads uploads
) {
    public AppProperties {
        finnhub = finnhub == null ? new Finnhub("not-set", "https://finnhub.io/api/v1",
                Duration.ofSeconds(10), Duration.ofSeconds(30)) : finnhub;
        yahoo = yahoo == null ? new Yahoo("https://query1.finance.yahoo.com/v8/finance/chart",
                Duration.ofSeconds(10), Duration.ofMinutes(5)) : yahoo;
        chat = chat == null ? new Chat(40) : chat;
        rag = rag == null ? new Rag(6, 0.25, 900, 120) : rag;
        uploads = uploads == null ? new Uploads("data/uploads") : uploads;
    }

    public record Finnhub(String apiKey, String baseUrl, Duration timeout, Duration cacheTtl) {
    }

    public record Yahoo(String baseUrl, Duration timeout, Duration cacheTtl) {
    }

    public record Chat(Integer maxMessages) {
    }

    public record Rag(Integer topK, Double similarityThreshold, Integer chunkSize, Integer chunkOverlap) {
    }

    public record Uploads(String directory) {
    }
}
