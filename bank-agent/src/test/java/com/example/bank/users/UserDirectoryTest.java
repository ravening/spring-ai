package com.example.bank.users;

import org.junit.jupiter.api.Test;

import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDirectoryTest {

    private final UserDirectory directory = new UserDirectory();

    @Test
    void listsSeededDemoUsers() {
        assertEquals(3, directory.list().size());
        assertTrue(directory.list().stream().anyMatch(u -> u.id().equals("alice")));
    }

    @Test
    void resolvesCaseInsensitively() {
        assertEquals("Alice Anderson", directory.resolve("ALICE").displayName());
        assertEquals("bob", directory.resolve(" Bob ").id());
    }

    @Test
    void rejectsUnknownUsers() {
        assertTrue(directory.find("mallory").isEmpty());
        assertThrows(ResponseStatusException.class, () -> directory.resolve("mallory"));
    }
}
