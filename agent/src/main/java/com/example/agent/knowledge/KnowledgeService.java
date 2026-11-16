package com.example.agent.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 * A small RAG knowledge base for the agent demo. At startup it loads a persisted
 * vector store or indexes the classpath knowledge documents, and exposes a plain
 * similarity search. It is wired into the agent as the {@code knowledge_search} tool.
 */
@Service
public class KnowledgeService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final String METADATA_SOURCE = "source";

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "markdown");

    private final SimpleVectorStore vectorStore;
    private final KnowledgeProperties properties;
    private final TokenTextSplitter textSplitter;

    private boolean populated;

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
            ReindexResult result = reindex();
            log.info("Indexed {} knowledge document(s) into {} chunk(s)", result.documents(), result.chunks());
        }
        catch (Exception ex) {
            log.error("Failed to index knowledge documents; knowledge_search will return no results", ex);
        }
    }

    public List<Document> search(String query, int topK) {
        if (!properties.enabled() || !populated || !StringUtils.hasText(query)) {
            return List.of();
        }
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, topK))
                .similarityThreshold(properties.similarityThreshold())
                .build();
        return vectorStore.similaritySearch(request);
    }

    public synchronized ReindexResult reindex() {
        List<Resource> resources = resolveDocuments();
        int chunks = 0;
        for (Resource resource : resources) {
            String source = sourceName(resource);
            try {
                List<Document> documents = new TikaDocumentReader(resource).get();
                vectorStore.delete(new FilterExpressionBuilder().eq(METADATA_SOURCE, source).build());
                chunks += addDocuments(documents, source);
            }
            catch (Exception ex) {
                log.warn("Failed to index {}", source, ex);
            }
        }
        save();
        return new ReindexResult(resources.size(), chunks);
    }

    private int addDocuments(List<Document> documents, String source) {
        List<Document> chunks = textSplitter.apply(documents.stream().filter(Document::isText).toList());
        for (Document chunk : chunks) {
            chunk.getMetadata().put(METADATA_SOURCE, source);
        }
        if (!chunks.isEmpty()) {
            vectorStore.add(chunks);
            populated = true;
        }
        return chunks.size();
    }

    private List<Resource> resolveDocuments() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            return Arrays.stream(resolver.getResources(properties.classpathDocsLocation()))
                    .filter(Resource::isReadable)
                    .filter(resource -> resource.getFilename() != null)
                    .filter(this::isSupported)
                    .sorted(Comparator.comparing(KnowledgeService::sourceName))
                    .toList();
        }
        catch (IOException ex) {
            log.warn("Could not resolve knowledge documents at {}", properties.classpathDocsLocation(), ex);
            return List.of();
        }
    }

    private boolean isSupported(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 && dot < filename.length() - 1
                && SUPPORTED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
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
