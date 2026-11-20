package com.example.bank.push;

import java.util.List;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One row per device token, so a user can have several devices registered. */
@Repository
public class PushDeviceStore {

    private final JdbcTemplate jdbc;

    public PushDeviceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS push_devices (
                    token VARCHAR(255) PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    platform VARCHAR(32),
                    created_at BIGINT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_push_devices_user ON push_devices (user_id)");
    }

    public void register(String userId, String token, String platform, long createdAt) {
        jdbc.update("""
                MERGE INTO push_devices (token, user_id, platform, created_at) KEY(token)
                VALUES (?, ?, ?, ?)
                """, token, userId, platform == null ? "unknown" : platform, createdAt);
    }

    public boolean unregisterToken(String userId, String token) {
        return jdbc.update("DELETE FROM push_devices WHERE token = ? AND user_id = ?", token, userId) > 0;
    }

    public int unregisterAll(String userId) {
        return jdbc.update("DELETE FROM push_devices WHERE user_id = ?", userId);
    }

    public void removeToken(String token) {
        jdbc.update("DELETE FROM push_devices WHERE token = ?", token);
    }

    public List<String> tokensFor(String userId) {
        return jdbc.query("SELECT token FROM push_devices WHERE user_id = ? ORDER BY created_at",
                (rs, rowNum) -> rs.getString(1), userId);
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM push_devices", Long.class);
        return value == null ? 0 : value;
    }

    public long countForUser(String userId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM push_devices WHERE user_id = ?", Long.class, userId);
        return value == null ? 0 : value;
    }
}
