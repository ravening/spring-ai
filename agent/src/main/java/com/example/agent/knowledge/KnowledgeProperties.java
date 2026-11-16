package com.example.agent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.rag")
public record KnowledgeProperties(
        boolean enabled,
        int topK,
        double similarityThreshold,
        int chunkSize,
        String vectorStoreFile,
        String classpathDocsLocation) {
}
