package com.example.deeprelay.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        int topK,
        double similarityThreshold,
        int chunkSize,
        String vectorStoreFile,
        boolean ingestClasspathDocs,
        String classpathDocsLocation,
        boolean ocrEnabled,
        String ocrLanguage,
        int ocrTimeoutSeconds) {
}
