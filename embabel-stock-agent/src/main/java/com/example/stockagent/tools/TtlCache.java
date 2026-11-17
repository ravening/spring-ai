package com.example.stockagent.tools;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Small in-memory TTL cache to respect free-tier market-data rate limits. */
public final class TtlCache {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public <T> T get(String key, Duration ttl, Supplier<T> loader) {
        Entry entry = entries.get(key);
        if (entry != null && !entry.expired()) {
            return (T) entry.value;
        }
        T value = loader.get();
        entries.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
        return value;
    }

    public void clear() {
        entries.clear();
    }

    private static final class Entry {
        private final Object value;
        private final long expiresAt;

        private Entry(Object value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        private boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
