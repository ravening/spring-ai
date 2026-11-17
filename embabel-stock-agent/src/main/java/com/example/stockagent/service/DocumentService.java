package com.example.stockagent.service;

import com.example.stockagent.config.AppProperties;
import com.example.stockagent.domain.DocumentScope;
import com.example.stockagent.domain.DocumentSnippet;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Tika ingestion + pgvector retrieval with global / per-chat scope isolation. */
@Service
public class DocumentService {

    public record DocumentInfo(String id, String filename, String scope, String conversationId,
                               long sizeBytes, int chunks, long uploadedAtEpochSeconds) {
    }

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final TokenTextSplitter splitter;
    private final Path uploadDir;

    public DocumentService(VectorStore vectorStore, JdbcTemplate jdbc, AppProperties properties) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.properties = properties;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(properties.rag().chunkSize())
                .withMinChunkSizeChars(properties.rag().chunkOverlap())
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(2000)
                .withKeepSeparator(true)
                .build();
        this.uploadDir = Path.of(properties.uploads().directory()).toAbsolutePath();
    }

    @PostConstruct
    void createTable() throws IOException {
        Files.createDirectories(uploadDir);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id VARCHAR(64) PRIMARY KEY,
                    filename VARCHAR(512) NOT NULL,
                    scope VARCHAR(16) NOT NULL,
                    conversation_id VARCHAR(64),
                    size_bytes BIGINT NOT NULL,
                    chunks INTEGER NOT NULL,
                    uploaded_at BIGINT NOT NULL
                )
                """);
    }

    public DocumentInfo upload(MultipartFile file, DocumentScope scope, String conversationId) throws IOException {
        String id = UUID.randomUUID().toString();
        String filename = sanitize(file.getOriginalFilename() == null ? "document" : file.getOriginalFilename());
        String target = id + "__" + filename;
        Path path = uploadDir.resolve(target);
        try (var in = file.getInputStream()) {
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        }

        List<Document> parsed = new TikaDocumentReader(new FileSystemResource(path.toFile())).get();
        String text = parsed.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        if (text.isBlank()) {
            Files.deleteIfExists(path);
            throw new IllegalArgumentException("No readable text found in " + filename);
        }

        List<Document> chunks = splitter.split(new Document(text)).stream()
                .map(chunk -> new Document(chunk.getText(), metadata(id, filename, scope, conversationId)))
                .toList();
        vectorStore.add(chunks);

        String scopeValue = scope.scopeValue(conversationId);
        jdbc.update("INSERT INTO documents (id, filename, scope, conversation_id, size_bytes, chunks, uploaded_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, filename, scopeValue, scope == DocumentScope.GLOBAL ? null : conversationId,
                file.getSize(), chunks.size(), Instant.now().getEpochSecond());

        return new DocumentInfo(id, filename, scopeValue,
                scope == DocumentScope.GLOBAL ? null : conversationId,
                file.getSize(), chunks.size(), Instant.now().getEpochSecond());
    }

    private Map<String, Object> metadata(String id, String filename, DocumentScope scope, String conversationId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", id);
        metadata.put("filename", filename);
        metadata.put("scope", scope.scopeValue(conversationId));
        metadata.put("conversation_id", scope == DocumentScope.GLOBAL ? "" : conversationId);
        metadata.put("source", "uploaded-document");
        return metadata;
    }

    /** Global documents plus documents scoped to this conversation only. */
    public List<DocumentInfo> listVisible(String conversationId) {
        return jdbc.query("SELECT * FROM documents WHERE scope = 'GLOBAL' OR conversation_id = ? "
                        + "ORDER BY uploaded_at DESC",
                (rs, rowNum) -> new DocumentInfo(rs.getString("id"), rs.getString("filename"),
                        rs.getString("scope"), rs.getString("conversation_id"),
                        rs.getLong("size_bytes"), rs.getInt("chunks"), rs.getLong("uploaded_at")),
                conversationId == null ? "" : conversationId);
    }

    public List<DocumentInfo> listGlobal() {
        return jdbc.query("SELECT * FROM documents WHERE scope = 'GLOBAL' ORDER BY uploaded_at DESC",
                (rs, rowNum) -> new DocumentInfo(rs.getString("id"), rs.getString("filename"),
                        rs.getString("scope"), rs.getString("conversation_id"),
                        rs.getLong("size_bytes"), rs.getInt("chunks"), rs.getLong("uploaded_at")));
    }

    public List<DocumentInfo> listForChat(String conversationId) {
        return jdbc.query("SELECT * FROM documents WHERE conversation_id = ? ORDER BY uploaded_at DESC",
                (rs, rowNum) -> new DocumentInfo(rs.getString("id"), rs.getString("filename"),
                        rs.getString("scope"), rs.getString("conversation_id"),
                        rs.getLong("size_bytes"), rs.getInt("chunks"), rs.getLong("uploaded_at")),
                conversationId);
    }

    public List<DocumentSnippet> search(String query, String conversationId) {
        return search(query, conversationId, properties.rag().topK());
    }

    public List<DocumentSnippet> search(String query, String conversationId, int topK) {
        String cid = escape(conversationId == null ? "" : conversationId);
        String filter = "scope == 'GLOBAL' || scope == 'CHAT:" + cid + "'";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(properties.rag().similarityThreshold())
                .filterExpression(filter)
                .build());
        if (results == null) {
            return List.of();
        }
        List<DocumentSnippet> snippets = new ArrayList<>();
        for (Document document : results) {
            Map<String, Object> meta = document.getMetadata();
            snippets.add(new DocumentSnippet(
                    String.valueOf(meta.getOrDefault("doc_id", "")),
                    String.valueOf(meta.getOrDefault("filename", "")),
                    String.valueOf(meta.getOrDefault("scope", "")),
                    document.getText(),
                    document.getScore() == null ? 0 : document.getScore()
            ));
        }
        return snippets;
    }

    public void delete(String documentId) {
        String filename = jdbc.query("SELECT filename FROM documents WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, documentId);
        vectorStore.delete("doc_id == '" + escape(documentId) + "'");
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        if (filename != null) {
            try {
                Files.deleteIfExists(uploadDir.resolve(documentId + "__" + filename));
            } catch (IOException ignored) {
                // best effort: the vector and metadata rows are already gone
            }
        }
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }

    private static String sanitize(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
