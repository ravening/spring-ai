package com.example.deeprelay.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RagRetrievalService {

    private static final int HISTORY_USER_MESSAGES = 4;

    private static final int SNIPPET_CHARS = 300;

    private static final int MAX_EXPANDED_CHUNKS_PER_SOURCE = 8;

    private static final int MAX_CONTEXT_CHUNKS = 12;

    private static final Pattern REFERENCE_WORDS = Pattern.compile(
            "\\b(it|its|it's|this|that|these|those|they|them|their|above|same|file|document|doc|pdf|more)\\b");

    private static final Pattern FOLLOW_UP_START = Pattern.compile("^(and|also|what about|how about)\\b");

    private static final Pattern FOLLOW_UP_WORDS = Pattern.compile(
            "\\b(rule|rules|first|second|third|next|previous|last|detail|details|summary|summarize|example|examples|section|chapter|page)\\b");

    private final VectorStore vectorStore;

    private final ChatMemory chatMemory;

    private final RagProperties properties;

    public RagRetrievalService(VectorStore vectorStore, ChatMemory chatMemory, RagProperties properties) {
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.properties = properties;
    }

    public List<Document> retrieve(String query, String conversationId, int topK) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        if (!StringUtils.hasText(conversationId)) {
            return expandSources(search(query, topK), query);
        }
        boolean followUp = isFollowUp(query);
        if (!followUp) {
            List<Document> direct = search(query, topK);
            if (!direct.isEmpty()) {
                return expandSources(direct, query);
            }
        }
        List<String> anchors = recentUserQueries(query, conversationId);
        List<String> specificAnchors = anchors.stream().filter(anchor -> !isFollowUp(anchor)).toList();
        if (!specificAnchors.isEmpty()) {
            anchors = specificAnchors;
        }
        List<Document> prioritized = new ArrayList<>();
        for (String anchor : anchors) {
            addUnique(prioritized, search(anchor, topK));
        }
        addUnique(prioritized, search(query, topK));
        return expandSources(prioritized, query);
    }

    private List<Document> expandSources(List<Document> documents, String query) {
        if (documents.isEmpty()) {
            return documents;
        }
        Map<String, Integer> smallSources = new LinkedHashMap<>();
        for (Document document : documents) {
            Object source = document.getMetadata().get("source");
            Object totalChunks = document.getMetadata().get("total_chunks");
            if (source instanceof String sourceName && totalChunks instanceof Number total
                    && total.intValue() <= MAX_EXPANDED_CHUNKS_PER_SOURCE) {
                smallSources.put(sourceName, total.intValue());
            }
        }
        if (smallSources.isEmpty()) {
            return documents.stream().limit(MAX_CONTEXT_CHUNKS).toList();
        }

        List<Document> expanded = new ArrayList<>(documents);
        for (Map.Entry<String, Integer> source : smallSources.entrySet()) {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(source.getValue())
                    .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                    .filterExpression(new FilterExpressionBuilder().eq("source", source.getKey()).build())
                    .build();
            addUnique(expanded, this.vectorStore.similaritySearch(request));
        }
        return expanded.stream().limit(MAX_CONTEXT_CHUNKS).toList();
    }

    private static void addUnique(List<Document> target, List<Document> documents) {
        for (Document document : documents) {
            boolean present = target.stream().anyMatch(existing -> existing.getId().equals(document.getId()));
            if (!present) {
                target.add(document);
            }
        }
    }

    private List<String> recentUserQueries(String currentQuery, String conversationId) {
        List<Message> history = this.chatMemory.get(conversationId);
        List<String> queries = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && queries.size() < HISTORY_USER_MESSAGES; i--) {
            Message message = history.get(i);
            if (message.getMessageType() != MessageType.USER) {
                continue;
            }
            String text = message.getText();
            if (!StringUtils.hasText(text) || normalize(text).equals(normalize(currentQuery))) {
                continue;
            }
            queries.add(snippet(text));
        }
        return queries;
    }

    private List<Document> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(this.properties.similarityThreshold())
                .build();
        return this.vectorStore.similaritySearch(request);
    }

    private static boolean isFollowUp(String query) {
        String normalized = normalize(query);
        return normalized.length() <= 8
                || REFERENCE_WORDS.matcher(normalized).find()
                || FOLLOW_UP_START.matcher(normalized).find()
                || FOLLOW_UP_WORDS.matcher(normalized).find();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String snippet(String text) {
        String normalized = text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= SNIPPET_CHARS ? normalized : normalized.substring(0, SNIPPET_CHARS);
    }
}
