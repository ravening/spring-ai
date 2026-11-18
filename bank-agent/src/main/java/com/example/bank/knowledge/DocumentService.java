package com.example.bank.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles uploaded files: stores the original under the uploads directory, extracts
 * text with Tika, splits it into chunks and indexes the chunks into the vector store
 * with a scope ({@code user} = visible in all of the user's conversations,
 * {@code chat} = visible only in one conversation). Metadata rows live in H2.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final JdbcTemplate jdbc;
    private final KnowledgeService knowledgeService;
    private final KnowledgeProperties properties;
    private final Path uploadDir;

    public DocumentService(JdbcTemplate jdbc, KnowledgeService knowledgeService, KnowledgeProperties properties) {
        this.jdbc = jdbc;
        this.knowledgeService = knowledgeService;
        this.properties = properties;
        this.uploadDir = Path.of(properties.uploadsDirectory()).toAbsolutePath();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(uploadDir);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id VARCHAR(64) PRIMARY KEY,
                    filename VARCHAR(512) NOT NULL,
                    scope VARCHAR(16) NOT NULL,
                    conversation_id VARCHAR(64),
                    user_id VARCHAR(64) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    chunks INTEGER NOT NULL,
                    uploaded_at BIGINT NOT NULL
                )
                """);
    }

    public record DocumentInfo(String id, String filename, String scope, String conversationId,
            String userId, long sizeBytes, int chunks, long uploadedAtEpochSeconds) {
    }

    public DocumentInfo upload(MultipartFile file, String scope, String conversationId, String userId) {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RAG is disabled (bank.rag.enabled=false)");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        String normalizedScope = normalizeScope(scope);
        if (KnowledgeService.SCOPE_CHAT.equals(normalizedScope) && !StringUtils.hasText(conversationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "conversationId is required when scope is 'chat'");
        }
        if (!StringUtils.hasText(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        String filename = sanitize(file.getOriginalFilename());
        if (!knowledgeService.isSupportedFile(filename)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unsupported file type for '" + filename + "'.");
        }

        String id = UUID.randomUUID().toString();
        Path target = uploadDir.resolve(id + "__" + filename);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the upload", ex);
        }

        try {
            List<Document> parsed = new TikaDocumentReader(new FileSystemResource(target.toFile())).get();
            String text = parsed.stream().map(Document::getText).filter(t -> t != null).reduce("", (a, b) -> a + "\n\n" + b);
            if (text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No readable text was found in " + filename);
            }
            List<Document> chunks = knowledgeService.textSplitter().apply(List.of(new Document(text)));
            Map<String, Object> metadata = new HashMap<>(KnowledgeService.uploadMetadata(
                    id, filename, normalizedScope, conversationId, userId));
            for (Document chunk : chunks) {
                chunk.getMetadata().putAll(metadata);
            }
            int chunkCount = knowledgeService.addDocuments(chunks);
            if (chunkCount == 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No indexable chunks were produced from " + filename);
            }
            long now = Instant.now().getEpochSecond();
            jdbc.update("INSERT INTO documents (id, filename, scope, conversation_id, user_id, size_bytes, chunks, uploaded_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    id, filename, normalizedScope,
                    KnowledgeService.SCOPE_CHAT.equals(normalizedScope) ? conversationId : null,
                    userId, file.getSize(), chunkCount, now);
            log.info("Indexed upload {} ({}) as {} chunk(s) with scope={}", filename, id, chunkCount, normalizedScope);
            return new DocumentInfo(id, filename, normalizedScope,
                    KnowledgeService.SCOPE_CHAT.equals(normalizedScope) ? conversationId : null,
                    userId, file.getSize(), chunkCount, now);
        }
        catch (ResponseStatusException ex) {
            deleteQuietly(target);
            throw ex;
        }
        catch (Exception ex) {
            deleteQuietly(target);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not parse " + filename + ": " + ex.getMessage(), ex);
        }
    }

    /** Documents visible in a conversation: the user's own uploads plus this chat's uploads. */
    public List<DocumentInfo> listVisible(String conversationId, String userId) {
        return jdbc.query("SELECT id, filename, scope, conversation_id, user_id, size_bytes, chunks, uploaded_at "
                        + "FROM documents WHERE (scope = 'user' AND user_id = ?) "
                        + "OR (scope = 'chat' AND conversation_id = ?) ORDER BY uploaded_at DESC",
                (rs, rowNum) -> map(rs), userId, conversationId);
    }

    public List<DocumentInfo> listForUser(String userId) {
        return jdbc.query("SELECT id, filename, scope, conversation_id, user_id, size_bytes, chunks, uploaded_at "
                        + "FROM documents WHERE user_id = ? ORDER BY uploaded_at DESC",
                (rs, rowNum) -> map(rs), userId);
    }

    public void delete(String documentId, String userId) {
        DocumentInfo info = jdbc.query("SELECT id, filename, scope, conversation_id, user_id, size_bytes, chunks, uploaded_at "
                        + "FROM documents WHERE id = ?", (rs, rowNum) -> map(rs), documentId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        if (!info.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Document belongs to another user");
        }
        knowledgeService.deleteByDocId(documentId);
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        deleteQuietly(uploadDir.resolve(documentId + "__" + info.filename()));
    }

    private static DocumentInfo map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocumentInfo(rs.getString("id"), rs.getString("filename"), rs.getString("scope"),
                rs.getString("conversation_id"), rs.getString("user_id"), rs.getLong("size_bytes"),
                rs.getInt("chunks"), rs.getLong("uploaded_at"));
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return KnowledgeService.SCOPE_CHAT;
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(KnowledgeService.SCOPE_CHAT) && !normalized.equals(KnowledgeService.SCOPE_USER)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope must be 'chat' or 'user', got '" + scope + "'");
        }
        return normalized;
    }

    private static String sanitize(String filename) {
        String name = filename == null || filename.isBlank() ? "document" : filename.trim();
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
        }
    }
}
