package com.example.agent.temporal;

import jakarta.annotation.PostConstruct;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Dedupe table for the {@code remember} activity. The {@code run_id} primary key makes
 * the memory write idempotent across activity retries after a crash: a repeated claim
 * returns {@code false} and the messages are not appended a second time.
 */
@Repository
public class AgentRunMemoryStore {

    private final JdbcTemplate jdbc;

    public AgentRunMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void createTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_run_memory (
                    run_id VARCHAR(64) PRIMARY KEY,
                    created_at BIGINT NOT NULL
                )
                """);
    }

    /** Returns true the first time this run writes memory, false on any retry. */
    public boolean claim(String runId) {
        try {
            jdbc.update("INSERT INTO agent_run_memory (run_id, created_at) VALUES (?, ?)",
                    runId, System.currentTimeMillis());
            return true;
        }
        catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
