package com.example.stockagent.domain;

/** A retrieved chunk with its scope so the UI can show where it came from. */
public record DocumentSnippet(
        String documentId,
        String filename,
        String scope,
        String excerpt,
        double score
) {
}
