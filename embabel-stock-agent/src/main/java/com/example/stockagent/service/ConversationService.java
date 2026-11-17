package com.example.stockagent.service;

import com.example.stockagent.config.AppProperties;
import com.example.stockagent.domain.ChatTurn;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Conversation metadata plus durable per-conversation chat memory in Postgres. */
@Service
public class ConversationService {

    public record ConversationInfo(String id, String title, long createdAtEpochSeconds, int messageCount) {
    }

    private final JdbcTemplate jdbc;
    private final ChatMemoryRepository repository;
    private final ChatMemory chatMemory;
    private final int maxMessages;

    public ConversationService(JdbcTemplate jdbc, ChatMemoryRepository repository, AppProperties properties) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.maxMessages = properties.chat().maxMessages();
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id VARCHAR(64) PRIMARY KEY,
                    title VARCHAR(255) NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """);
    }

    public void create(String id, String title) {
        jdbc.update("INSERT INTO conversations (id, title, created_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                id, title == null || title.isBlank() ? "New analysis" : title, Instant.now().getEpochSecond());
    }

    public List<ConversationInfo> list() {
        return jdbc.query("SELECT c.id, c.title, c.created_at, "
                        + "(SELECT COUNT(*) FROM spring_ai_chat_memory m WHERE m.conversation_id = c.id) AS messages "
                        + "FROM conversations c ORDER BY c.created_at DESC",
                (rs, rowNum) -> new ConversationInfo(rs.getString("id"), rs.getString("title"),
                        rs.getLong("created_at"), rs.getInt("messages")));
    }

    public void rename(String id, String title) {
        jdbc.update("UPDATE conversations SET title = ? WHERE id = ?", title, id);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM conversations WHERE id = ?", id);
        repository.deleteByConversationId(id);
    }

    public List<ChatTurn> history(String conversationId) {
        List<ChatTurn> turns = new ArrayList<>();
        for (Message message : repository.findByConversationId(conversationId)) {
            String role = message instanceof UserMessage ? "user"
                    : message instanceof AssistantMessage ? "assistant" : "system";
            turns.add(new ChatTurn(role, message.getText()));
        }
        if (turns.size() > maxMessages) {
            return new ArrayList<>(turns.subList(turns.size() - maxMessages, turns.size()));
        }
        return turns;
    }

    /** Persist a completed turn. Only full messages are ever stored. */
    public void saveTurn(String conversationId, String userText, String assistantText) {
        // An MCP/API caller may send a conversationId that was never created via the UI.
        String title = userText.length() > 60 ? userText.substring(0, 60) : userText;
        create(conversationId, title);
        // Give UI-created chats a meaningful title after their first turn.
        jdbc.update("UPDATE conversations SET title = ? WHERE id = ? AND title = 'New analysis'",
                title, conversationId);
        chatMemory.add(conversationId, List.of(
                new UserMessage(userText),
                new AssistantMessage(assistantText)
        ));
    }
}
