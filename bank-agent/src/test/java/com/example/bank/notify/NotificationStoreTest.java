package com.example.bank.notify;

import java.util.List;
import java.util.UUID;

import com.example.bank.notify.NotificationDtos.NotificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationStoreTest {

    private NotificationStore store;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:n_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        store = new NotificationStore(jdbc);
        store.createTable();
    }

    private long insert(String user, String dedupe, long createdAt) {
        return store.insert(user, "conv-1", "transfer_received", "Incoming", "You got money",
                "{\"amount\":\"10.00\"}", null, dedupe, createdAt);
    }

    @Test
    void idsAreMonotonicAndDedupeIsIdempotent() {
        long a = insert("alice", "transfer:T1", 1000);
        long b = insert("alice", "transfer:T2", 1000); // same timestamp, different id
        assertTrue(a > 0);
        assertTrue(b > a, "equal created_at must not collapse the cursor");

        long duplicate = insert("alice", "transfer:T1", 1000);
        assertEquals(Long.MIN_VALUE, duplicate);

        List<NotificationDto> all = store.list("alice", 0, 50, false);
        assertEquals(2, all.size());
        assertEquals(a, all.get(0).id());
        assertEquals(b, all.get(1).id());
    }

    @Test
    void cursorReturnsOnlyNewerRows() {
        long a = insert("alice", "k1", 1);
        long b = insert("alice", "k2", 2);
        List<NotificationDto> page = store.list("alice", a, 50, false);
        assertEquals(1, page.size());
        assertEquals(b, page.get(0).id());
    }

    @Test
    void unreadCountAndMarkReadArePerUserAndIdempotent() {
        long a = insert("alice", "k1", 1);
        insert("alice", "k2", 2);
        insert("bob", "k3", 3);
        assertEquals(2, store.unreadCount("alice"));
        assertEquals(1, store.unreadCount("bob"));

        assertTrue(store.markRead(a, "alice", 10));
        assertFalse(store.markRead(a, "alice", 11), "second mark-read is a no-op");
        assertFalse(store.markRead(a, "bob", 11), "other user cannot mark it read");
        assertEquals(1, store.unreadCount("alice"));

        assertEquals(1, store.markAllRead("alice", 12));
        assertEquals(0, store.unreadCount("alice"));
    }

    @Test
    void usersCannotSeeOrDeleteEachOthersRows() {
        long id = insert("alice", "k1", 1);
        assertTrue(store.existsForUser(id, "alice"));
        assertFalse(store.existsForUser(id, "bob"));
        assertTrue(store.list("bob", 0, 50, false).isEmpty());
        assertFalse(store.delete(id, "bob"));
        assertTrue(store.delete(id, "alice"));
        assertTrue(store.list("alice", 0, 50, false).isEmpty());
    }

    @Test
    void unreadOnlyAndMaxIdWork() {
        long a = insert("alice", "k1", 1);
        long b = insert("alice", "k2", 2);
        store.markRead(a, "alice", 5);
        List<NotificationDto> unread = store.list("alice", 0, 50, true);
        assertEquals(1, unread.size());
        assertEquals(b, unread.get(0).id());
        assertEquals(b, store.maxId("alice").orElseThrow());
        assertNotEquals(0, store.maxId("alice").orElseThrow());
    }
}
