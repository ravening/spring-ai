package com.example.bank.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conversation metadata (one row per conversation, bound to a user) plus durable
 * per-conversation chat memory in H2 via the Spring AI JDBC repository.
 */
@Service
public class ConversationService {

    public static final String DEFAULT_TITLE = "New conversation";

    public record ConversationRow(String id, String userId, String title, long createdAtEpochSeconds, String kind) {
    }

    /** Deterministic id of the lazily-created SYSTEM chat used for notifications. */
    public static String systemConversationId(String userId) {
        return "notify-" + userId;
    }

    private final JdbcTemplate jdbc;
    private final ChatMemoryRepository repository;
    private final ChatMemory chatMemory;
    private final ObjectProvider<com.example.bank.jobs.JobService> jobService;

    public ConversationService(JdbcTemplate jdbc, ChatMemoryRepository repository, ChatMemory chatMemory,
            ObjectProvider<com.example.bank.jobs.JobService> jobService) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.chatMemory = chatMemory;
        this.jobService = jobService;
    }

    @PostConstruct
    public void createTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    created_at BIGINT NOT NULL,
                    kind VARCHAR(16) NOT NULL DEFAULT 'CHAT',
                    updated_at BIGINT NOT NULL DEFAULT 0
                )
                """);
        // Additive migration for databases created before these columns existed.
        jdbc.execute("ALTER TABLE conversations ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'CHAT'");
        jdbc.execute("ALTER TABLE conversations ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0");
    }

    public String create(String userId, String conversationId) {
        return create(userId, conversationId, "CHAT");
    }

    public String create(String userId, String conversationId, String kind) {
        long now = Instant.now().getEpochSecond();
        jdbc.update("""
                INSERT INTO conversations (id, user_id, title, kind, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, conversationId, userId, DEFAULT_TITLE, kind, now, now);
        return conversationId;
    }

    /**
     * Deterministic, idempotent SYSTEM chat for a user's notifications. Uses an
     * INSERT ... WHERE NOT EXISTS so concurrent callers cannot create two rows.
     */
    public String systemConversationFor(String userId) {
        String id = systemConversationId(userId);
        try {
            jdbc.update("""
                    INSERT INTO conversations (id, user_id, title, kind, created_at, updated_at)
                    SELECT ?, ?, ?, 'SYSTEM', ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM conversations WHERE id = ?)
                    """, id, userId, "Bank notifications",
                    Instant.now().getEpochSecond(), Instant.now().getEpochSecond(), id);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // Another caller created it first — deterministic id means it is equivalent.
        }
        return id;
    }

    /** Most recently updated conversation of a user, used as the transfer recipient chat. */
    public Optional<String> mostRecentConversationFor(String userId) {
        return jdbc.query("""
                        SELECT id FROM conversations WHERE user_id = ?
                        ORDER BY updated_at DESC, created_at DESC LIMIT 1
                        """, (rs, rowNum) -> rs.getString(1), userId)
                .stream().findFirst();
    }

    public boolean exists(String conversationId) {
        return !jdbc.query("SELECT id FROM conversations WHERE id = ?", (rs, rowNum) -> rs.getString(1),
                conversationId).isEmpty();
    }

    public ConversationRow get(String conversationId) {
        return find(conversationId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    public Optional<ConversationRow> find(String conversationId) {
        return jdbc.query("SELECT id, user_id, title, created_at, kind FROM conversations WHERE id = ?",
                        (rs, rowNum) -> new ConversationRow(rs.getString("id"), rs.getString("user_id"),
                                rs.getString("title"), rs.getLong("created_at"), rs.getString("kind")),
                        conversationId)
                .stream().findFirst();
    }

    public List<ConversationRow> listForUser(String userId) {
        return jdbc.query("SELECT id, user_id, title, created_at, kind FROM conversations WHERE user_id = ? "
                        + "ORDER BY updated_at DESC, created_at DESC",
                        (rs, rowNum) -> new ConversationRow(rs.getString("id"),
                                rs.getString("user_id"), rs.getString("title"), rs.getLong("created_at"),
                                rs.getString("kind")), userId);
    }

    public void ensureOwnership(String conversationId, String userId) {
        ConversationRow row = get(conversationId);
        if (!row.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Conversation belongs to another user");
        }
    }

    public void delete(String conversationId) {
        // Cancel jobs first so none can fire into a deleted conversation (SYSTEM
        // notification chats are deliberately not reachable through the REST delete).
        com.example.bank.jobs.JobService service = jobService.getIfAvailable();
        if (service != null) {
            service.cancelJobsForConversation(conversationId);
        }
        jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);
        chatMemory.clear(conversationId);
    }

    /** Gives a conversation a meaningful title after its first user message. */
    public void titleFromFirstMessage(String conversationId, String firstUserMessage) {
        String title = firstUserMessage.replaceAll("\\s+", " ").strip();
        if (title.length() > 70) {
            title = title.substring(0, 70).strip() + "...";
        }
        jdbc.update("UPDATE conversations SET title = ? WHERE id = ? AND title = ?",
                title.isEmpty() ? DEFAULT_TITLE : title, conversationId, DEFAULT_TITLE);
    }

    public List<Message> history(String conversationId) {
        return repository.findByConversationId(conversationId);
    }

    public void addUserMessage(String conversationId, String text) {
        chatMemory.add(conversationId, List.of(new UserMessage(text)));
        touch(conversationId);
    }

    public void addAssistantMessage(String conversationId, String text) {
        chatMemory.add(conversationId, List.of(new AssistantMessage(text)));
        touch(conversationId);
    }

    private void touch(String conversationId) {
        jdbc.update("UPDATE conversations SET updated_at = ? WHERE id = ?",
                Instant.now().getEpochSecond(), conversationId);
    }
}
