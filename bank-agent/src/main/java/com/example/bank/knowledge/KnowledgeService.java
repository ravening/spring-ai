package com.example.bank.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * RAG knowledge base for the bank app. At startup it loads a persisted vector store
 * or indexes the classpath bank documents (scope {@code global}). Uploaded documents
 * are added with scope {@code user} or {@code chat} so retrieval can be filtered per
 * customer and per conversation. The {@code knowledge_search} local tool is backed
 * by {@link #search(String, int, String, String)}.
 */
@Service
public class KnowledgeService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    public static final String METADATA_SOURCE = "source";
    public static final String METADATA_DOC_ID = "doc_id";
    public static final String METADATA_FILENAME = "filename";
    public static final String METADATA_SCOPE = "scope";
    public static final String METADATA_CONVERSATION_ID = "conversation_id";
    public static final String METADATA_USER_ID = "user_id";

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_CHAT = "chat";

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "pdf", "doc", "docx", "odt", "rtf",
            "html", "htm", "xml", "json", "csv", "ppt", "pptx", "xls", "xlsx");

    private final SimpleVectorStore vectorStore;
    private final KnowledgeProperties properties;
    private final TokenTextSplitter textSplitter;

    private volatile boolean populated;

    public KnowledgeService(SimpleVectorStore vectorStore, KnowledgeProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(Math.max(100, properties.chunkSize()))
                .build();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Knowledge base is disabled");
            return;
        }
        if (loadPersistedStore()) {
            return;
        }
        try {
            ReindexResult result = reindexClasspathDocuments();
            log.info("Indexed {} bank knowledge document(s) into {} chunk(s)", result.documents(), result.chunks());
        }
        catch (Exception ex) {
            log.error("Failed to index bank knowledge documents; knowledge_search will return no results", ex);
        }
    }

    /**
     * Similarity search restricted to what the given user and conversation may see:
     * global bank documents, documents the user uploaded for all their chats
     * (scope {@code user}), and documents uploaded to this conversation (scope
     * {@code chat}). Filtering happens in memory after retrieval because
     * SimpleVectorStore evaluates filter expressions itself and OR-combinations are
     * easier to reason about here.
     */
    public List<Document> search(String query, int topK, String conversationId, String userId) {
        if (!properties.enabled() || !populated || !StringUtils.hasText(query)) {
            return List.of();
        }
        int fetch = Math.max(topK, 1) * 4;
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(fetch)
                .similarityThreshold(properties.similarityThreshold())
                .build();
        return vectorStore.similaritySearch(request).stream()
                .filter(document -> isVisible(document.getMetadata(), conversationId, userId))
                .limit(Math.max(topK, 1))
                .toList();
    }

    public static boolean isVisible(Map<String, Object> metadata, String conversationId, String userId) {
        Object scope = metadata.get(METADATA_SCOPE);
        if (scope == null || SCOPE_GLOBAL.equals(scope)) {
            return true;
        }
        if (SCOPE_USER.equals(scope)) {
            return userId != null && userId.equals(metadata.get(METADATA_USER_ID));
        }
        if (SCOPE_CHAT.equals(scope)) {
            return conversationId != null && conversationId.equals(metadata.get(METADATA_CONVERSATION_ID));
        }
        return false;
    }

    public synchronized ReindexResult reindexClasspathDocuments() {
        List<Resource> resources = resolveClasspathDocuments();
        int chunks = 0;
        for (Resource resource : resources) {
            String source = sourceName(resource);
            try {
                List<Document> documents = new TikaDocumentReader(resource).get();
                vectorStore.delete(new FilterExpressionBuilder().eq(METADATA_SOURCE, source).build());
                chunks += addDocuments(documents, metadata(source, SCOPE_GLOBAL, null, null));
            }
            catch (Exception ex) {
                log.warn("Failed to index {}", source, ex);
            }
        }
        save();
        return new ReindexResult(resources.size(), chunks);
    }

    /** Adds already-split chunks that carry their own metadata, then persists the store. */
    public synchronized int addDocuments(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        vectorStore.add(chunks);
        populated = true;
        save();
        return chunks.size();
    }

    public synchronized void deleteByDocId(String docId) {
        vectorStore.delete(new FilterExpressionBuilder().eq(METADATA_DOC_ID, docId).build());
        save();
    }

    public TokenTextSplitter textSplitter() {
        return textSplitter;
    }

    public boolean isSupportedFile(String filename) {
        if (filename == null) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 && dot < filename.length() - 1
                && SUPPORTED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static Map<String, Object> metadata(String source, String scope, String conversationId, String userId) {
        return Map.of(
                METADATA_SOURCE, source == null ? "unknown" : source,
                METADATA_SCOPE, scope,
                METADATA_CONVERSATION_ID, conversationId == null ? "" : conversationId,
                METADATA_USER_ID, userId == null ? "" : userId);
    }

    /** Builds chunk metadata for an uploaded document. */
    public static Map<String, Object> uploadMetadata(String docId, String filename, String scope,
            String conversationId, String userId) {
        return Map.of(
                METADATA_SOURCE, filename,
                METADATA_DOC_ID, docId,
                METADATA_FILENAME, filename,
                METADATA_SCOPE, scope,
                METADATA_CONVERSATION_ID, conversationId == null ? "" : conversationId,
                METADATA_USER_ID, userId == null ? "" : userId);
    }

    private int addDocuments(List<Document> documents, Map<String, Object> metadata) {
        List<Document> chunks = textSplitter.apply(documents.stream().filter(Document::isText).toList());
        for (Document chunk : chunks) {
            chunk.getMetadata().putAll(metadata);
        }
        if (!chunks.isEmpty()) {
            vectorStore.add(chunks);
            populated = true;
        }
        return chunks.size();
    }

    private List<Resource> resolveClasspathDocuments() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            return Arrays.stream(resolver.getResources(properties.classpathDocsLocation()))
                    .filter(Resource::isReadable)
                    .filter(resource -> resource.getFilename() != null)
                    .filter(resource -> isSupportedFile(resource.getFilename()))
                    .sorted(Comparator.comparing(KnowledgeService::sourceName))
                    .toList();
        }
        catch (IOException ex) {
            log.warn("Could not resolve knowledge documents at {}", properties.classpathDocsLocation(), ex);
            return List.of();
        }
    }

    private boolean loadPersistedStore() {
        Path path = Path.of(properties.vectorStoreFile());
        try {
            if (Files.isRegularFile(path) && Files.size(path) > 0) {
                vectorStore.load(path.toFile());
                populated = true;
                log.info("Loaded knowledge vector store from {}", path.toAbsolutePath());
                return true;
            }
        }
        catch (Exception ex) {
            log.warn("Could not load knowledge vector store from {}, rebuilding from documents", path, ex);
        }
        return false;
    }

    private void save() {
        if (!populated) {
            return;
        }
        Path path = Path.of(properties.vectorStoreFile());
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            vectorStore.save(path.toFile());
        }
        catch (Exception ex) {
            log.warn("Could not persist knowledge vector store to {}", path, ex);
        }
    }

    private static String sourceName(Resource resource) {
        String filename = resource.getFilename();
        if (StringUtils.hasText(filename)) {
            return filename;
        }
        try {
            return resource.getURL().toString();
        }
        catch (IOException ex) {
            return resource.getDescription();
        }
    }

    public record ReindexResult(int documents, int chunks) {
    }
}
