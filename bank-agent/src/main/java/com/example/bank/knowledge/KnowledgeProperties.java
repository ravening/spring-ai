package com.example.bank.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank.rag")
public record KnowledgeProperties(
        boolean enabled,
        int topK,
        double similarityThreshold,
        int chunkSize,
        String vectorStoreFile,
        String classpathDocsLocation,
        String uploadsDirectory) {
}
