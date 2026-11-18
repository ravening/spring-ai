package com.example.bank.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression test for the H2 2.4.240 check-constraint bug (h2database/h2database#4302,
 * #4320): the SPRING_AI_CHAT_MEMORY table created by Spring AI's schema-h2.sql carries an
 * inline CHECK (type IN (...)) that 2.4.240 evaluates with a comparator bound to the
 * session that created it, so every INSERT from another connection fails with
 * "Check constraint invalid" (23514). Pinning h2.version to 2.5.252 fixes it.
 */
class H2ChatMemorySchemaTest {

    private static final String SCHEMA_RESOURCE =
            "org/springframework/ai/chat/memory/repository/jdbc/schema-h2.sql";

    @TempDir
    Path tempDir;

    @Test
    void validInsertSucceedsAfterSchemaCreatingConnectionIsClosed() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("bank-memory");
        createSchema(url);
        assertDoesNotThrow(() -> insert(url, "USER", "transfer money to other user"));
        assertDoesNotThrow(() -> insert(url, "ASSISTANT", "How can I help?"));
    }

    @Test
    void invalidTypeIsStillRejected() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("bank-memory");
        createSchema(url);
        assertThrows(SQLException.class, () -> insert(url, "NOT_A_TYPE", "hello"));
    }

    private void createSchema(String url) throws Exception {
        var resource = H2ChatMemorySchemaTest.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE);
        String schema = new String(Objects.requireNonNull(resource, "missing " + SCHEMA_RESOURCE).readAllBytes(),
                StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private void insert(String url, String type, String content) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp, sequence_id)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, content);
            ps.setString(3, type);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setLong(5, 0);
            ps.executeUpdate();
        }
    }
}
